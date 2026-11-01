package com.example.audio.adapters.output.ffmpeg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.io.input.ProxyInputStream;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.eclipse.microprofile.faulttolerance.Bulkhead;

import com.example.audio.adapters.output.ffmpeg.config.ProcessingConfig;
import com.example.audio.domain.exceptions.AudioProcessingException;
import com.example.audio.domain.exceptions.DomainException;
import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.ports.output.AudioProcessor;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link AudioProcessor} backed by the FFmpeg libraries through JavaCV. FFmpeg reads and writes plain streams,
 * one small buffer at a time.
 * <p>
 * Only the work that is needed is done: measuring a duration reads the packets without decoding them, and a
 * concatenation copies the packets of the parts that already have the codec parameters of the input.
 * <p>
 * Untrusted media hardening: FFmpeg only reads from the given stream (no file or network protocol), the demuxer is
 * forced to the declared format, the magic bytes are checked first and the duration is capped.
 */
@Slf4j
@ApplicationScoped
public class JavaCvAudioProcessor implements AudioProcessor {

    /** Default concurrent FFmpeg jobs per operation, overridden by FFMPEG_MAX_JOBS; further ones are rejected (503). */
    static final int MAX_CONCURRENT_JOBS = 8;

    private static final int OUTPUT_CHANNELS = 1;
    /** The highest sample rate in common use; a header can announce anything, which FFmpeg cannot resample. */
    private static final int MAX_SAMPLE_RATE = 384_000;
    /** 7.1 surround. */
    private static final int MAX_CHANNELS = 8;

    private final ProcessingConfig config;

    JavaCvAudioProcessor(final ProcessingConfig config) {
        this.config = config;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
    }

    @Override
    @Bulkhead(MAX_CONCURRENT_JOBS)
    public Duration process(final InputStream source, final AudioFormat format, final Map<AudioFormat, OutputStream> outputs) {
        if (outputs.isEmpty()) {
            return measure(source, format);
        }
        final JavaCvAudioProcessor.EndTrackingInputStream input = new EndTrackingInputStream(source);
        final List<FFmpegFrameRecorder> recorders = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = grabber(input, format)) {
            for (final Map.Entry<AudioFormat, OutputStream> output : outputs.entrySet()) {
                final AudioFormat target = output.getKey();
                recorders.add(recorder(output.getValue(), target, FfmpegFormatSpec.of(target).sampleRate(), OUTPUT_CHANNELS));
            }
            long samples = 0;
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                samples += samples(frame);
                for (final FFmpegFrameRecorder recorder : recorders) {
                    // the recorder resamples and downmixes to its own format
                    recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
                }
            }
            drain(input);
            for (final FFmpegFrameRecorder recorder : recorders) {
                recorder.stop();
            }
            return Duration.ofNanos(samples * 1_000_000_000L / grabber.getSampleRate());
        } catch (IOException | RuntimeException e) {
            throw translate(e);
        } finally {
            recorders.forEach(JavaCvAudioProcessor::release);
        }
    }

    /** Sums the durations of the packets: nothing is decoded. */
    private Duration measure(final InputStream source, final AudioFormat format) {
        final EndTrackingInputStream input = new EndTrackingInputStream(source);
        try (FFmpegFrameGrabber grabber = grabber(input, format)) {
            final Timeline timeline = new Timeline(audioStream(grabber.getFormatContext()).time_base());
            final PacketSource packets = new PacketSource(grabber);
            AVPacket packet;
            while ((packet = packets.next()) != null) {
                timeline.place(packet, packets);
                avcodec.av_packet_unref(packet);
                checkDuration(timeline);
            }
            drain(input);
            return timeline.elapsed();
        } catch (IOException | RuntimeException e) {
            throw translate(e);
        }
    }

    /**
     * The input sets the codec parameters of the output: its packets are copied as they are. A fixed sound is copied
     * too when it has the same parameters; otherwise only that sound is re-encoded to them, in memory (it is short).
     * The input is opened first, to know its parameters, but read only when its turn comes.
     */
    @Override
    @Bulkhead(MAX_CONCURRENT_JOBS)
    public void concatenate(final List<Supplier<InputStream>> parts, final int inputIndex, final AudioFormat format,
            final OutputStream output) {
        FFmpegFrameRecorder recorder = null;
        try (EndTrackingInputStream input = new EndTrackingInputStream(parts.get(inputIndex).get());
                FFmpegFrameGrabber reference = grabber(input, format)) {
            final AVStream referenceStream = audioStream(reference.getFormatContext());
            recorder = copyingRecorder(output, format, reference);
            final Timeline timeline = new Timeline(referenceStream.time_base());
            for (int i = 0; i < parts.size(); i++) {
                if (i == inputIndex) {
                    copy(new PacketSource(reference), referenceStream, recorder, timeline);
                    drain(input);
                } else {
                    append(parts.get(i).get(), format, referenceStream, recorder, timeline);
                }
            }
            recorder.stop();
        } catch (IOException | RuntimeException e) {
            throw translate(e);
        } finally {
            if (recorder != null) {
                release(recorder);
            }
        }
    }

    private void append(final InputStream source, final AudioFormat format, final AVStream reference, final FFmpegFrameRecorder recorder,
            final Timeline timeline) throws IOException {
        try (EndTrackingInputStream input = new EndTrackingInputStream(source); FFmpegFrameGrabber grabber = grabber(input, format)) {
            if (sameParameters(audioStream(grabber.getFormatContext()).codecpar(), reference.codecpar())) {
                copy(new PacketSource(grabber), reference, recorder, timeline);
                drain(input);
                return;
            }
            final byte[] adapted = encode(grabber, format, reference.codecpar());
            drain(input);
            try (FFmpegFrameGrabber adaptedGrabber = grabber(new ByteArrayInputStream(adapted), format)) {
                copy(new PacketSource(adaptedGrabber), reference, recorder, timeline);
            }
        }
    }

    /** Decodes a part and encodes it with the parameters of the input, into memory. */
    private byte[] encode(final FFmpegFrameGrabber grabber, final AudioFormat format, final AVCodecParameters parameters) throws IOException {
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        final FFmpegFrameRecorder recorder = recorder(encoded, format, parameters.sample_rate(), parameters.ch_layout().nb_channels());
        try {
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                samples(frame);
                recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
            }
            recorder.stop();
        } finally {
            release(recorder);
        }
        return encoded.toByteArray();
    }

    /** Writes the audio packets of a part after the previous ones. */
    private void copy(final PacketSource packets, final AVStream reference, final FFmpegFrameRecorder recorder, final Timeline timeline)
            throws IOException {
        AVPacket packet;
        while ((packet = packets.next()) != null) {
            timeline.place(packet, packets);
            packet.stream_index(reference.index());
            checkDuration(timeline);
            // the recorder converts the timestamps from the time base of the input stream, and frees the packet
            recorder.recordPacket(packet);
        }
    }

    private static boolean sameParameters(final AVCodecParameters part, final AVCodecParameters reference) {
        return part.codec_id() == reference.codec_id() && part.sample_rate() == reference.sample_rate()
                && part.ch_layout().nb_channels() == reference.ch_layout().nb_channels();
    }

    private void checkDuration(final Timeline timeline) {
        if (timeline.elapsed().compareTo(config.maxAudioDuration()) > 0) {
            throw new InvalidAudioException("The audio exceeds the maximum duration of " + config.maxAudioDuration());
        }
    }

    private FFmpegFrameGrabber grabber(final InputStream input, final AudioFormat format) throws IOException {
        final byte[] head = input.readNBytes(AudioFormat.SIGNATURE_LENGTH);
        if (!format.matchesSignature(head)) {
            throw new InvalidAudioException("The content is not a valid " + format.getCode() + " audio");
        }
        // maximumSize 0: no seeking, otherwise JavaCV would keep the whole input in memory to allow it
        final FFmpegFrameGrabber grabber = new UnlockedGrabber(new SequenceInputStream(new ByteArrayInputStream(head), input));
        final String demuxer = FfmpegFormatSpec.of(format).container();
        grabber.setFormat(demuxer);
        grabber.setOption("format_whitelist", demuxer);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.setCloseInputStream(false);
        grabber.start();
        // a valid header with nothing decodable behind it gives no audio parameters
        if (grabber.getSampleRate() <= 0 || grabber.getAudioChannels() <= 0) {
            grabber.close();
            throw new InvalidAudioException("The content has no decodable audio");
        }
        if (grabber.getSampleRate() > MAX_SAMPLE_RATE || grabber.getAudioChannels() > MAX_CHANNELS) {
            grabber.close();
            throw new InvalidAudioException("The audio has an unsupported sample rate or number of channels");
        }
        return grabber;
    }

    /** An encoder in the given format; the recorder resamples and remixes what it is given. */
    private static FFmpegFrameRecorder recorder(final OutputStream output, final AudioFormat format, final int sampleRate, final int channels)
            throws IOException {
        final FfmpegFormatSpec spec = FfmpegFormatSpec.of(format);
        final FFmpegFrameRecorder recorder = new UnlockedRecorder(output, channels);
        recorder.setCloseOutputStream(false);
        recorder.setFormat(spec.container());
        recorder.setAudioCodecName(spec.codec());
        recorder.setSampleRate(sampleRate);
        recorder.setAudioBitrate(spec.bitrate());
        recorder.start();
        return recorder;
    }

    /** A muxer copying the packets of the input: the codec parameters are the ones of its audio stream. */
    private static FFmpegFrameRecorder copyingRecorder(final OutputStream output, final AudioFormat format, final FFmpegFrameGrabber input)
            throws IOException {
        final FfmpegFormatSpec spec = FfmpegFormatSpec.of(format);
        final FFmpegFrameRecorder recorder = new UnlockedRecorder(output, input.getAudioChannels());
        recorder.setCloseOutputStream(false);
        recorder.setFormat(spec.container());
        // required to create the audio stream, then replaced by the parameters of the input
        recorder.setSampleRate(input.getSampleRate());
        recorder.setAudioBitrate(spec.bitrate());
        recorder.start(input.getFormatContext());
        return recorder;
    }

    private static AVStream audioStream(final AVFormatContext context) {
        for (int i = 0; i < context.nb_streams(); i++) {
            if (context.streams(i).codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                return context.streams(i);
            }
        }
        throw new InvalidAudioException("The content has no audio stream");
    }

    /** Samples per channel, checked against the maximum duration; grabbed frames are interleaved 16-bit. */
    private long samples(final Frame frame) {
        final long count = frame.samples == null ? 0 : frame.samples[0].limit() / Math.max(1, frame.audioChannels);
        if (frame.timestamp > config.maxAudioDuration().toNanos() / 1_000) {
            throw new InvalidAudioException("The audio exceeds the maximum duration of " + config.maxAudioDuration());
        }
        return count;
    }

    /**
     * FFmpeg takes a read failure for the end of the input: reading on surfaces it, and consumes what FFmpeg left.
     */
    private static void drain(final EndTrackingInputStream input) throws IOException {
        if (!input.ended) {
            input.transferTo(OutputStream.nullOutputStream());
        }
    }

    /** The audio packets of a grabber, with what is needed to time them. */
    private static final class PacketSource {

        private final FFmpegFrameGrabber grabber;
        private final AVStream stream;

        private PacketSource(final FFmpegFrameGrabber grabber) {
            this.grabber = grabber;
            this.stream = audioStream(grabber.getFormatContext());
        }

        /** The next audio packet, other streams (e.g. an MP3 cover picture) being skipped. */
        private AVPacket next() throws IOException {
            AVPacket packet;
            while ((packet = grabber.grabPacket()) != null) {
                if (packet.stream_index() == stream.index()) {
                    return packet;
                }
                avcodec.av_packet_unref(packet);
            }
            return null;
        }
    }

    /**
     * Places packets one after the other, in the time base of the input stream. Audio packets have no reordering:
     * each one starts where the previous one ends, whatever the timestamps of its own part.
     */
    private static final class Timeline {

        private final AVRational base;
        private long next;

        private Timeline(final AVRational base) {
            this.base = base;
        }

        private void place(final AVPacket packet, final PacketSource source) {
            final long duration = packet.duration() > 0
                    ? avutil.av_rescale_q(packet.duration(), source.stream.time_base(), base)
                    // a frame of the codec, when the demuxer does not tell
                    : avutil.av_rescale(source.stream.codecpar().frame_size(), base.den(),
                            (long) source.stream.codecpar().sample_rate() * base.num());
            if (duration <= 0) {
                throw new InvalidAudioException("The audio content could not be timed");
            }
            packet.pts(next);
            packet.dts(next);
            packet.duration(duration);
            packet.pos(-1);
            next += duration;
        }

        private Duration elapsed() {
            return Duration.ofNanos(avutil.av_rescale(next, 1_000_000_000L * base.num(), base.den()));
        }
    }

    /** Remembers the end of the stream: some streams fail when they are read again once ended. */
    private static final class EndTrackingInputStream extends ProxyInputStream {

        private boolean ended;

        private EndTrackingInputStream(final InputStream input) {
            super(input);
        }

        @Override
        protected void afterRead(final int n) {
            ended |= n < 0;
        }
    }

    /**
     * JavaCV serializes the opening and closing of every grabber and recorder of the JVM on one global lock, needed
     * by FFmpeg before 4.0. FFmpeg 7 is thread-safe there (it locks the few codecs that need it itself), so the
     * unlocked variants are used: concurrent jobs no longer wait for each other.
     * <p>
     * A grabber that fails to start is released at once. JavaCV registers the stream it reads in a static map, keyed
     * by the format context; when FFmpeg cannot open the input, it frees that context and JavaCPP zeroes the key:
     * JavaCV cannot unregister the stream any more (1.5.11, still the case upstream), and would keep it forever with
     * everything behind it. Those dead entries, the only ones with a null key, are removed here.
     */
    private static final class UnlockedGrabber extends FFmpegFrameGrabber {

        /** JavaCV's registry of the streams being read; empty if a JavaCV release changed it. */
        private static final Map<Pointer, InputStream> STREAMS = streamRegistry();

        private UnlockedGrabber(final InputStream input) {
            super(input, 0);
        }

        @Override
        public void start(final boolean findStreamInfo) throws Exception {
            try {
                startUnsafe(findStreamInfo);
            } catch (Exception e) {
                releaseUnsafe();
                forgetDeadStreams();
                throw e;
            }
        }

        private static void forgetDeadStreams() {
            // iterating a synchronized map requires its lock
            synchronized (STREAMS) {
                STREAMS.keySet().removeIf(key -> key.isNull());
            }
        }

        @SuppressWarnings({ "unchecked", "java:S3011" }) // the registry is package-private in JavaCV
        private static Map<Pointer, InputStream> streamRegistry() {
            try {
                final Field field = FFmpegFrameGrabber.class.getDeclaredField("inputStreams");
                field.setAccessible(true);
                return (Map<Pointer, InputStream>) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.warn("JavaCV stream registry not found: streams of inputs FFmpeg cannot open are kept", e);
                return new HashMap<>();
            }
        }

        @Override
        public void release() throws Exception {
            releaseUnsafe();
        }
    }

    /** See {@link UnlockedGrabber}. */
    private static final class UnlockedRecorder extends FFmpegFrameRecorder {

        private UnlockedRecorder(final OutputStream output, final int audioChannels) {
            super(output, audioChannels);
        }

        /** A recorder that fails to start is released at once: JavaCV keeps its output stream until then. */
        @Override
        public void start() throws Exception {
            try {
                startUnsafe();
            } catch (Exception e) {
                releaseUnsafe();
                throw e;
            }
        }

        @Override
        public void release() throws Exception {
            releaseUnsafe();
        }
    }

    private static void release(final FFmpegFrameRecorder recorder) {
        try {
            recorder.release();
        } catch (Exception e) {
            log.debug("Recorder release failed", e);
        }
    }

    private static RuntimeException translate(final Exception e) {
        if (e instanceof DomainException domain) {
            return domain;
        }
        if (e instanceof FrameGrabber.Exception) {
            log.debug("Undecodable audio", e);
            return new InvalidAudioException("The audio content could not be decoded", e);
        }
        log.error("Audio processing failed", e);
        return new AudioProcessingException("The audio could not be processed", e);
    }
}
