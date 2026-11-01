package com.example.audio.domain.models;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.audio.domain.exceptions.InvalidRequestException;

import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/** Aggregate root: an audio synchronized from the external service, with its stored variants. */
@Builder(toBuilder = true)
public record Audio(
        @NonNull AudioId id,
        @NonNull ExternalAudioId externalId,
        @NonNull String name,
        @NonNull Duration duration,
        @Singular List<AudioVariant> variants,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt) {

    public static final int MAX_NAME_LENGTH = 255;

    public List<AudioFormat> formats() {
        return variants.stream().map(AudioVariant::format).toList();
    }

    public Optional<AudioVariant> variant(final FormatSelector selector) {
        return selector.resolve(formats())
                .flatMap(format -> variants.stream().filter(v -> v.format() == format).findFirst());
    }

    public Audio rename(final String newName, final Instant now) {
        return toBuilder().name(validName(newName)).updatedAt(now).build();
    }

    public static String validName(final String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidRequestException("Name must be 1 to " + MAX_NAME_LENGTH + " printable characters");
        }
        return name.strip();
    }
}
