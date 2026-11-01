package com.example.audio.domain.models;

/** One element of a concatenation template. */
public sealed interface TemplatePart {

    record FixedSound(SoundName name) implements TemplatePart {
    }

    record Input() implements TemplatePart {
    }
}
