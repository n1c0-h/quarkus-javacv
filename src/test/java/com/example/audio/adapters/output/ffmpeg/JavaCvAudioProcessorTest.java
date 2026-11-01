package com.example.audio.adapters.output.ffmpeg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.audio.domain.exceptions.ExternalServiceException;
import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.AudioFixtures;

class JavaCvAudioProcessorTest {

    /** AMR frames are 20 ms and MP3 encoders add padding: a few frames of tolerance. */
    private static final Duration TOLERANCE = Duration.ofMillis(120);

    private final JavaCvAudioProcessor processor = new JavaCvAudioProcessor(() -> Duration.ofMinutes(15));

    static Stream<Arguments> samplesInEveryFormat() {
        return AudioFixtures.SAMPLES.stream()
                .flatMap(name -> Arrays.stream(AudioFormat.values()).map(format -> Arguments.of(name, format)));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("samplesInEveryFormat")
    void measuresTheDurationWhileReadingTheStream(final String name, final AudioFormat format) {
        final Path file = AudioFixtures.sample(name, format);

        final Duration duration = processor.process(input(file), format, Map.of());

        Assertions.assertThat(duration).isCloseTo(AudioFixtures.duration(file), TOLERANCE);
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource
    void decodesOnceIntoEveryRequestedFormat(final AudioFormat source) {
        final Path file = AudioFixtures.sample("dustofsnow_wt", source);
        final Map<AudioFormat, ByteArrayOutputStream> outputs = Map.of(
                AudioFormat.MP3, new ByteArrayOutputStream(),
                AudioFormat.AMR_NB, new ByteArrayOutputStream(),
                AudioFormat.AMR_WB, new ByteArrayOutputStream());

        processor.process(input(file), source, Map.copyOf(outputs));

        outputs.forEach((target, output) -> {
            final byte[] encoded = output.toByteArray();
            Assertions.assertThat(target.matchesSignature(Arrays.copyOf(encoded, AudioFormat.SIGNATURE_LENGTH))).as(target.getCode()).isTrue();
            Assertions.assertThat(AudioFixtures.duration(encoded)).as(target.getCode()).isCloseTo(AudioFixtures.duration(file), TOLERANCE.multipliedBy(2));
        });
    }

    /** The MP3 samples are at 22.05 kHz and the MP3 fixed sounds at 48 kHz: the output keeps the rate of the input. */
    @ParameterizedTest
    @EnumSource(names = { "MP3", "AMR_NB", "AMR_WB" })
    void concatenatesFixedSoundsAroundTheInputWithTheParametersOfTheInput(final AudioFormat format) {
        final Path input = AudioFixtures.sample("stars_jg", format);
        final List<Path> files = List.of(AudioFixtures.fixedSound("a", format), input, AudioFixtures.fixedSound("b", format));

        final byte[] output = concatenate(files, 1, format);

        final Duration expected = files.stream().map(AudioFixtures::duration).reduce(Duration.ZERO, Duration::plus);
        Assertions.assertThat(format.matchesSignature(Arrays.copyOf(output, AudioFormat.SIGNATURE_LENGTH))).isTrue();
        Assertions.assertThat(AudioFixtures.sampleRate(output)).isEqualTo(AudioFixtures.sampleRate(AudioFixtures.bytes(input)));
        Assertions.assertThat(AudioFixtures.duration(output)).isCloseTo(expected, TOLERANCE.multipliedBy(3));
    }

    @ParameterizedTest
    @EnumSource(names = { "MP3", "AMR_NB", "AMR_WB" })
    void copiesTheInputWithoutReencodingIt(final AudioFormat format) {
        final byte[] input = AudioFixtures.bytes(AudioFixtures.sample("dustofsnow_wt", format));
        final List<Path> files = List.of(AudioFixtures.sample("dustofsnow_wt", format), AudioFixtures.fixedSound("d", format));

        final byte[] output = concatenate(files, 0, format);

        // a re-encoded input would not contain the original frames
        final byte[] middle = Arrays.copyOfRange(input, input.length / 4, input.length / 2);
        Assertions.assertThat(indexOf(output, middle)).isNotNegative();
    }

    @Test
    void readsTheInputToItsEnd() throws IOException {
        final InputStream input = input(AudioFixtures.sample("stars_jg", AudioFormat.MP3));

        processor.process(input, AudioFormat.MP3, Map.of());

        Assertions.assertThat(input.available()).isZero();
    }

    @Test
    void rejectsContentNotMatchingItsDeclaredFormat() throws IOException {
        final Map<AudioFormat, OutputStream> noOutput = Map.of();
        try (InputStream mp3 = input(AudioFixtures.sample("stars_jg", AudioFormat.MP3))) {
            Assertions.assertThatThrownBy(() -> processor.process(mp3, AudioFormat.AMR_WB, noOutput))
                    .isInstanceOf(InvalidAudioException.class);
        }
    }

    @Test
    void rejectsGarbageBehindAValidSignature() {
        final byte[] garbage = new byte[64 * 1024];
        System.arraycopy("#!AMR-WB\n".getBytes(), 0, garbage, 0, 9);
        Arrays.fill(garbage, 9, garbage.length, (byte) 0x5A);
        final InputStream input = new ByteArrayInputStream(garbage);
        final Map<AudioFormat, OutputStream> noOutput = Map.of();

        Assertions.assertThatThrownBy(() -> processor.process(input, AudioFormat.AMR_WB, noOutput))
                .isInstanceOf(InvalidAudioException.class);
    }

    /** Found by fuzzing: FFmpeg cannot resample from the rate a WAV header announces, whatever it is. */
    @Test
    void rejectsAnAbsurdSampleRate() throws IOException {
        final byte[] wav = AudioFixtures.bytes(AudioFixtures.sample("stars_jg", AudioFormat.WAV));
        // sample rate field of the canonical WAV header
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(24, Integer.MAX_VALUE);
        final Map<AudioFormat, OutputStream> outputs = Map.of(AudioFormat.MP3, OutputStream.nullOutputStream());
        try (InputStream input = new ByteArrayInputStream(wav)) {
            Assertions.assertThatThrownBy(() -> processor.process(input, AudioFormat.WAV, outputs))
                    .isInstanceOf(InvalidAudioException.class);
        }
    }

    @Test
    void rejectsAudioLongerThanTheLimit() throws IOException {
        final JavaCvAudioProcessor limited = new JavaCvAudioProcessor(() -> Duration.ofSeconds(10));
        final Map<AudioFormat, OutputStream> noOutput = Map.of();
        try (InputStream input = input(AudioFixtures.sample("stars_jg", AudioFormat.MP3))) {
            Assertions.assertThatThrownBy(() -> limited.process(input, AudioFormat.MP3, noOutput))
                    .isInstanceOf(InvalidAudioException.class).hasMessageContaining("maximum duration");
        }
    }

    @Test
    void reportsAnInputFailureInsteadOfATruncatedResult() {
        final byte[] data = AudioFixtures.bytes(AudioFixtures.sample("stars_jg", AudioFormat.AMR_WB));
        // half of the audio, then a connection failure on every read
        final SequenceInputStream failing = new SequenceInputStream(new ByteArrayInputStream(data, 0, data.length / 2), new InputStream() {
            @Override
            public int read() {
                throw new ExternalServiceException("connection lost");
            }
        });
        final Map<AudioFormat, OutputStream> outputs = Map.of(AudioFormat.MP3, OutputStream.nullOutputStream());

        Assertions.assertThatThrownBy(() -> processor.process(failing, AudioFormat.AMR_WB, outputs))
                .isInstanceOf(ExternalServiceException.class);
    }

    private byte[] concatenate(final List<Path> files, final int inputIndex, final AudioFormat format) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        processor.concatenate(files.stream().<Supplier<InputStream>>map(file -> () -> input(file)).toList(), inputIndex, format, output);
        return output.toByteArray();
    }

    private static int indexOf(final byte[] data, final byte[] part) {
        for (int i = 0; i <= data.length - part.length; i++) {
            if (Arrays.equals(data, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        return -1;
    }

    private static InputStream input(final Path file) {
        return new ByteArrayInputStream(AudioFixtures.bytes(file));
    }
}
