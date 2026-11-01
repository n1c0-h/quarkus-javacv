package com.example.audio.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import com.example.audio.domain.models.AudioFormat;

/**
 * Test audio files, all public domain (see the ATTRIBUTION files), stored in every format: one folder per format.
 */
public final class AudioFixtures {

    public static final List<String> SAMPLES = List.of("stars_jg", "dustofsnow_wt", "aplusbsquared_mlo", "herlips_tp",
            "forme_el", "sonnet20_cjph", "sonnetviii_ss", "darkness_dg", "autumntwilight_ik", "songofmyselfone_clp",
            "nymphsreply_cm", "greatthings_asm");
    public static final List<AudioFormat> PROPOSED = List.of(AudioFormat.MP3, AudioFormat.AMR_NB, AudioFormat.AMR_WB);

    /** Also served by the WireMock mock of the external service. */
    private static final Path SAMPLES_DIR = Path.of("src/test/resources/wiremock/__files/samples");
    /** Uploaded to the S3 emulator at startup. */
    private static final Path FIXED_SOUNDS_DIR = Path.of("src/test/resources/localstack/fixed-sounds");

    private AudioFixtures() {
    }

    public static Path sample(final String name, final AudioFormat format) {
        return file(SAMPLES_DIR, name, format);
    }

    public static Path fixedSound(final String name, final AudioFormat format) {
        return file(FIXED_SOUNDS_DIR, name, format);
    }

    public static Stream<Path> allSamples(final AudioFormat format) {
        return SAMPLES.stream().map(name -> sample(name, format));
    }

    public static byte[] bytes(final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Decodes a whole file, independently of the code under test. */
    public static Duration duration(final byte[] audio) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(audio))) {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();
            long samples = 0;
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                samples += frame.samples[0].limit() / frame.audioChannels;
            }
            return Duration.ofNanos(samples * 1_000_000_000L / grabber.getSampleRate());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Duration duration(final Path path) {
        return duration(bytes(path));
    }

    /** The sample rate of the audio stream, as read by FFmpeg. */
    public static int sampleRate(final byte[] audio) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(audio))) {
            grabber.start();
            return grabber.getSampleRate();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path file(final Path directory, final String name, final AudioFormat format) {
        return directory.resolve(format.getCode()).resolve(name + "." + format.getExtension());
    }
}
