package com.example.audio.domain.models;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.audio.domain.exceptions.InvalidRequestException;

class FormatSelectorTest {

    @ParameterizedTest(name = "{0} among [{1}] → {2}")
    @CsvSource(delimiter = '|', value = {
            "amr    | mp3 amr-nb amr-wb | amr-wb",
            "amr    | mp3 amr-nb        | amr-nb",
            "amr    | mp3               | -",
            "amr-nb | mp3 amr-wb        | -",
            "mp3    | mp3 amr-wb        | mp3"
    })
    void resolvesTheStoredVariant(final String selector, final String available, final String expected) {
        final List<AudioFormat> formats = Arrays.stream(available.trim().split(" ")).map(code -> AudioFormat.fromCode(code).orElseThrow()).toList();

        final Optional<AudioFormat> resolved = FormatSelector.fromCode(selector.trim()).resolve(formats);

        Assertions.assertThat(resolved).isEqualTo(expected.equals("-") ? Optional.empty() : AudioFormat.fromCode(expected));
    }

    @Test
    void transcodesPlainAmrToWideBand() {
        Assertions.assertThat(FormatSelector.AMR.transcodingTarget()).isEqualTo(AudioFormat.AMR_WB);
    }

    @Test
    void doesNotOfferWav() {
        Assertions.assertThatThrownBy(() -> FormatSelector.fromCode("wav")).isInstanceOf(InvalidRequestException.class);
        Assertions.assertThat(List.of(FormatSelector.values())).extracting(FormatSelector::transcodingTarget)
                .doesNotContain(AudioFormat.WAV);
    }
}
