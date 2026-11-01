package com.example.audio.adapters.output.ffmpeg;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.assertj.core.api.Assertions;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.junit.jupiter.api.Test;

import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.AudioFixtures;

/** Repeated jobs, including failing ones, must give native memory back. */
class JavaCvAudioProcessorLeakTest {

    private static final int CYCLES = 30;

    private final JavaCvAudioProcessor processor = new JavaCvAudioProcessor(() -> Duration.ofMinutes(15));

    @Test
    void releasesNativeMemory() {
        final byte[] input = AudioFixtures.bytes(AudioFixtures.sample("dustofsnow_wt", AudioFormat.AMR_WB));
        runCycles(5, input);
        final long baseline = Pointer.totalBytes();

        runCycles(CYCLES, input);

        // tolerance for lazily created native caches, far below one leaked job per cycle
        Assertions.assertThat(Pointer.totalBytes()).isLessThan(baseline + 1024 * 1024);
    }

    /**
     * Inputs FFmpeg cannot open: JavaCV registers every stream in static maps, which must not keep them (with what
     * is behind them: downloads, uploads) once the job failed.
     */
    @Test
    void forgetsTheStreamsOfFailedJobs() throws ReflectiveOperationException, IOException {
        final byte[] garbage = new byte[64 * 1024];
        Arrays.fill(garbage, (byte) 0x5A);
        System.arraycopy("ID3".getBytes(StandardCharsets.US_ASCII), 0, garbage, 0, 3);
        final byte[] sound = AudioFixtures.bytes(AudioFixtures.fixedSound("a", AudioFormat.MP3));
        final int inputs = registry(FFmpegFrameGrabber.class, "inputStreams").size();
        final int outputs = registry(FFmpegFrameRecorder.class, "outputStreams").size();

        final Map<AudioFormat, OutputStream> transcoding = Map.of(AudioFormat.AMR_WB, OutputStream.nullOutputStream());
        final List<Supplier<InputStream>> parts = List.of(() -> new ByteArrayInputStream(sound), () -> new ByteArrayInputStream(garbage));
        try (OutputStream output = OutputStream.nullOutputStream()) {
            for (int i = 0; i < CYCLES; i++) {
                final InputStream input = new ByteArrayInputStream(garbage);
                Assertions.assertThatThrownBy(() -> processor.process(input, AudioFormat.MP3, transcoding))
                        .isInstanceOf(InvalidAudioException.class);
                Assertions.assertThatThrownBy(() -> processor.concatenate(parts, 1, AudioFormat.MP3, output))
                        .isInstanceOf(InvalidAudioException.class);
            }
        }

        Assertions.assertThat(registry(FFmpegFrameGrabber.class, "inputStreams")).hasSize(inputs);
        Assertions.assertThat(registry(FFmpegFrameRecorder.class, "outputStreams")).hasSize(outputs);
    }

    @SuppressWarnings("java:S3011") // the registries are package-private in JavaCV
    private static Map<?, ?> registry(final Class<?> type, final String name) throws ReflectiveOperationException {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    /** Packet copies, and the re-encoding of fixed sounds whose rate differs from the input (MP3). */
    @Test
    void releasesNativeMemoryOfConcatenations() {
        final byte[] input = AudioFixtures.bytes(AudioFixtures.sample("dustofsnow_wt", AudioFormat.MP3));
        final byte[] sound = AudioFixtures.bytes(AudioFixtures.fixedSound("a", AudioFormat.MP3));
        concatenationCycles(5, input, sound);
        final long baseline = Pointer.totalBytes();

        concatenationCycles(CYCLES, input, sound);

        Assertions.assertThat(Pointer.totalBytes()).isLessThan(baseline + 1024 * 1024);
    }

    private void concatenationCycles(final int cycles, final byte[] input, final byte[] sound) {
        for (int i = 0; i < cycles; i++) {
            processor.concatenate(List.of(() -> new ByteArrayInputStream(sound), () -> new ByteArrayInputStream(input)), 1,
                    AudioFormat.MP3, OutputStream.nullOutputStream());
            try {
                processor.concatenate(List.of(() -> new ByteArrayInputStream(input, 0, input.length / 2), () -> new ByteArrayInputStream(sound)),
                        0, AudioFormat.MP3, OutputStream.nullOutputStream());
            } catch (RuntimeException expected) {
                // a truncated stream may also end cleanly at its last complete frame
            }
        }
    }

    private void runCycles(final int cycles, final byte[] input) {
        for (int i = 0; i < cycles; i++) {
            processor.process(new ByteArrayInputStream(input), AudioFormat.AMR_WB,
                    Map.of(AudioFormat.MP3, OutputStream.nullOutputStream(), AudioFormat.AMR_NB, OutputStream.nullOutputStream()));
            try {
                // truncated input: the job fails half way
                processor.process(new ByteArrayInputStream(input, 0, input.length / 2), AudioFormat.AMR_WB,
                        Map.of(AudioFormat.MP3, OutputStream.nullOutputStream()));
            } catch (RuntimeException expected) {
                // a truncated AMR stream may also decode cleanly up to its last complete frame
            }
        }
    }
}
