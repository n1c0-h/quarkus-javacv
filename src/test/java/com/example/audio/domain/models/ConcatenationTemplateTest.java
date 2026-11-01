package com.example.audio.domain.models;

import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.audio.domain.exceptions.InvalidRequestException;

class ConcatenationTemplateTest {

    @ParameterizedTest
    @ValueSource(strings = { "a,INPUT,b", "c,INPUT", "INPUT,d", "a,a,INPUT,b-2" })
    void acceptsFixedSoundsAroundOneInput(final String tokens) {
        final ConcatenationTemplate template = ConcatenationTemplate.parse(List.of(tokens.split(",")));

        Assertions.assertThat(template.parts()).hasSize(tokens.split(",").length)
                .filteredOn(TemplatePart.Input.class::isInstance).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INPUT", // nothing to add
            "a,b", // no input
            "INPUT,a,INPUT", // two inputs
            "a,input", // the token is case-sensitive: this is a sound named "input", so no input
            "../secret,INPUT", // path traversal in a storage key
            "A,INPUT", // uppercase is outside the allowed charset
            "a,b,c,d,a,b,c,d,a,b,INPUT" // too many parts
    })
    void rejectsInvalidTemplates(final String tokens) {
        final List<String> parts = Arrays.asList(tokens.split(","));

        Assertions.assertThatThrownBy(() -> ConcatenationTemplate.parse(parts)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void listsEachFixedSoundOnce() {
        final ConcatenationTemplate template = ConcatenationTemplate.parse(List.of("a", "INPUT", "a", "b"));

        Assertions.assertThat(template.fixedSounds()).containsExactly(SoundName.of("a"), SoundName.of("b"));
    }
}
