package com.example.audio.domain.models;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

import com.example.audio.domain.exceptions.InvalidRequestException;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Format requested by a client. {@code amr} means "whatever AMR variant exists", wide band first.
 */
@Getter
@RequiredArgsConstructor
public enum FormatSelector {
    MP3("mp3"),
    AMR("amr"),
    AMR_NB("amr-nb"),
    AMR_WB("amr-wb");

    private final String code;

    public static FormatSelector fromCode(final String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("Supported formats are mp3, amr, amr-nb and amr-wb"));
    }

    /** Picks the matching format among the available ones. */
    public Optional<AudioFormat> resolve(final Collection<AudioFormat> available) {
        return switch (this) {
            case MP3 -> pick(available, AudioFormat.MP3);
            case AMR_NB -> pick(available, AudioFormat.AMR_NB);
            case AMR_WB -> pick(available, AudioFormat.AMR_WB);
            case AMR -> pick(available, AudioFormat.AMR_WB).or(() -> pick(available, AudioFormat.AMR_NB));
        };
    }

    /** Output format of a transcoding: plain {@code amr} produces wide band. */
    public AudioFormat transcodingTarget() {
        return switch (this) {
            case MP3 -> AudioFormat.MP3;
            case AMR, AMR_WB -> AudioFormat.AMR_WB;
            case AMR_NB -> AudioFormat.AMR_NB;
        };
    }

    private static Optional<AudioFormat> pick(final Collection<AudioFormat> available, final AudioFormat format) {
        return available.contains(format) ? Optional.of(format) : Optional.empty();
    }
}
