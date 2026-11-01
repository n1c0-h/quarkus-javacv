package com.example.audio.adapters.output.ffmpeg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.assertj.core.api.Assertions;
import org.bytedeco.javacpp.Pointer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.AudioFixtures;
import com.example.audio.support.AudioMutator;
import com.example.audio.support.FuzzCaseFile;

/**
 * Mutation fuzzing of the audio read from users and from the external service. For every mutated input, each path
 * of FFmpeg either succeeds with a valid output or rejects the content as invalid audio (422): no other failure, no
 * hang, no native memory kept. A failure prints its seed and index; a JVM crash leaves the case in
 * {@code target/fuzz}. {@code mvn verify -Pfuzz} (or {@code -Dfuzz.cases=N -Dfuzz.seed=S}) runs a longer campaign.
 */
class JavaCvAudioProcessorFuzzTest {

    private static final long SEED = Long.getLong("fuzz.seed", 20_261_101L);
    /** One case per mutation strategy and format by default; the fuzz profile runs a long campaign. */
    private static final int CASES_PER_FORMAT = Integer.getInteger("fuzz.cases", 12);
    /** Far above the few hundred milliseconds a sample takes: only a hang reaches it. */
    private static final long TIMEOUT_SECONDS = 30;
    private static final Duration MAX_DURATION = Duration.ofMinutes(15);

    private final JavaCvAudioProcessor processor = new JavaCvAudioProcessor(() -> MAX_DURATION);
    /** The jobs are independent: they run on half of the CPUs, the other half being left to FFmpeg's own threads. */
    private final ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    private final FuzzCaseFile currentCases = new FuzzCaseFile("JavaCvAudioProcessorFuzzTest");

    @AfterEach
    void stop() {
        executor.shutdownNow();
        currentCases.close();
    }

    @Test
    void handlesMutatedAudioCleanly() {
        runAll(3);
        final long baseline = Pointer.totalBytes();

        final List<String> failures = runAll(CASES_PER_FORMAT);

        Assertions.assertThat(failures).isEmpty();
        // native memory of the failed jobs is given back, like the one of the successful ones
        Assertions.assertThat(Pointer.totalBytes()).isLessThan(baseline + 4 * 1024 * 1024);
    }

    /** A job and its name, which is its seed, index and mutation. */
    private record Job(String name, Runnable work) {
    }

    private List<String> runAll(final int cases) {
        final List<Job> jobs = new ArrayList<>();
        for (final AudioFormat format : AudioFormat.values()) {
            final byte[] valid = AudioFixtures.bytes(AudioFixtures.sample("stars_jg", format));
            for (int index = 0; index < cases; index++) {
                final AudioMutator.Mutation mutation = AudioMutator.mutate(valid, format, SEED, index);
                final String name = format.getCode() + " #" + index + " (" + mutation.description() + ")";
                jobs.add(new Job(name + " transcode", () -> transcode(mutation.data(), format)));
                jobs.add(new Job(name + " duration", () -> processor.process(input(mutation.data()), format, Map.of())));
                if (format.isProposed()) {
                    jobs.add(new Job(name + " concatenate", () -> concatenate(mutation.data(), format)));
                }
            }
        }
        final List<Future<?>> results = jobs.stream()
                .<Future<?>>map(job -> executor.submit(() -> currentCases.run(job.name() + " (seed " + SEED + ")", job.work())))
                .toList();
        final List<String> failures = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            failure(jobs.get(i).name(), results.get(i)).ifPresent(failures::add);
        }
        return failures;
    }

    /**
     * The unexpected outcome of a job, if any: they are collected, not thrown, to report every failing case. The
     * jobs before it are done when it is waited for, so the timeout only covers its own run.
     */
    private static Optional<String> failure(final String name, final Future<?> result) {
        try {
            result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Optional.empty();
        } catch (TimeoutException e) {
            result.cancel(true);
            return Optional.of(name + ": no result after " + TIMEOUT_SECONDS + " s (seed " + SEED + ")");
        } catch (ExecutionException e) {
            return e.getCause() instanceof InvalidAudioException ? Optional.empty()
                    : Optional.of(name + ": " + e.getCause() + " (seed " + SEED + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(name + ": interrupted");
        }
    }

    private void transcode(final byte[] data, final AudioFormat format) {
        final Map<AudioFormat, ByteArrayOutputStream> outputs = Map.of(AudioFormat.MP3, new ByteArrayOutputStream(),
                AudioFormat.AMR_WB, new ByteArrayOutputStream());
        final Duration duration = processor.process(input(data), format, Map.copyOf(outputs));
        Assertions.assertThat(duration).isBetween(Duration.ZERO, MAX_DURATION);
        outputs.forEach((target, output) -> assertValid(target, output.toByteArray()));
    }

    private void concatenate(final byte[] data, final AudioFormat format) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<Supplier<InputStream>> parts = List.of(
                () -> input(AudioFixtures.bytes(AudioFixtures.fixedSound("a", format))), () -> input(data));
        processor.concatenate(parts, 1, format, output);
        assertValid(format, output.toByteArray());
    }

    /** Whatever was decodable, the output is a well-formed file of its format. */
    private static void assertValid(final AudioFormat format, final byte[] output) {
        Assertions.assertThat(format.matchesSignature(Arrays.copyOf(output, AudioFormat.SIGNATURE_LENGTH))).as(format.getCode()).isTrue();
    }

    private static InputStream input(final byte[] data) {
        return new ByteArrayInputStream(data);
    }
}
