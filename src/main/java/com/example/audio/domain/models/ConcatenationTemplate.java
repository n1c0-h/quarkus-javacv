package com.example.audio.domain.models;

import java.util.List;

import com.example.audio.domain.exceptions.InvalidRequestException;

/**
 * Ordered fixed sounds around exactly one input, e.g. {@code a, INPUT, b}.
 */
public record ConcatenationTemplate(List<TemplatePart> parts) {

    public static final String INPUT_TOKEN = "INPUT";
    public static final int MAX_PARTS = 10;

    public ConcatenationTemplate {
        if (parts == null || parts.size() < 2 || parts.size() > MAX_PARTS) {
            throw new InvalidRequestException("A template has 2 to " + MAX_PARTS + " parts");
        }
        if (parts.stream().filter(TemplatePart.Input.class::isInstance).count() != 1) {
            throw new InvalidRequestException("A template contains " + INPUT_TOKEN + " exactly once");
        }
        parts = List.copyOf(parts);
    }

    public static ConcatenationTemplate parse(final List<String> tokens) {
        if (tokens == null) {
            throw new InvalidRequestException("A template has 2 to " + MAX_PARTS + " parts");
        }
        return new ConcatenationTemplate(tokens.stream()
                .map(token -> INPUT_TOKEN.equals(token)
                        ? (TemplatePart) new TemplatePart.Input()
                        : new TemplatePart.FixedSound(SoundName.of(token)))
                .toList());
    }

    public List<SoundName> fixedSounds() {
        return parts.stream()
                .filter(TemplatePart.FixedSound.class::isInstance)
                .map(p -> ((TemplatePart.FixedSound) p).name())
                .distinct()
                .toList();
    }
}
