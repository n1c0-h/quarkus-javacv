package com.example.audio.domain.models;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/** Audio as described by the external service, which may offer several formats of it. */
@Builder
public record ExternalAudio(@NonNull ExternalAudioId id, @NonNull String name, @Singular List<ExternalAudioFormat> formats) {

    public ExternalAudio {
        formats = formats == null ? List.of() : List.copyOf(formats);
    }

    public Set<AudioFormat> availableFormats() {
        return formats.stream().map(ExternalAudioFormat::format).collect(Collectors.toUnmodifiableSet());
    }

    public OptionalLong sizeOf(final AudioFormat format) {
        return formats.stream()
                .filter(f -> f.format() == format)
                .mapToLong(ExternalAudioFormat::size)
                .findFirst();
    }
}
