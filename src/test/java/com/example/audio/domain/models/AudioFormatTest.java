package com.example.audio.domain.models;

import java.util.Arrays;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.example.audio.support.AudioFixtures;

class AudioFormatTest {

    /** Each real file is recognized as its own format only, so content cannot pass for another format. */
    @ParameterizedTest
    @EnumSource
    void recognizesOnlyItsOwnSignature(final AudioFormat actual) {
        AudioFixtures.allSamples(actual).forEach(file -> {
            final byte[] head = Arrays.copyOf(AudioFixtures.bytes(file), AudioFormat.SIGNATURE_LENGTH);
            for (final AudioFormat declared : AudioFormat.values()) {
                Assertions.assertThat(declared.matchesSignature(head)).as("%s declared as %s", file, declared).isEqualTo(declared == actual);
            }
        });
    }

    @ParameterizedTest
    @CsvSource({
            "audio/mpeg, MP3", "audio/mp3, MP3", "audio/wav, WAV", "audio/x-wav, WAV", "audio/wave, WAV",
            "audio/amr, AMR_NB", "audio/amr-wb, AMR_WB", "AUDIO/AMR-WB; charset=binary, AMR_WB"
    })
    void resolvesMimeTypesAndCommonAliases(final String mimeType, final AudioFormat expected) {
        Assertions.assertThat(AudioFormat.fromMimeType(mimeType)).contains(expected);
    }
}
