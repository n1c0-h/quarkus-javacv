package com.example.audio.domain.models;

import java.util.UUID;

import com.example.audio.domain.exceptions.InvalidRequestException;

import lombok.NonNull;

/** Identifier of an audio in the external catalog. */
public record ExternalAudioId(@NonNull UUID value) {

    public static ExternalAudioId of(final UUID value) {
        return new ExternalAudioId(value);
    }

    public static ExternalAudioId parse(final String value) {
        try {
            return of(UUID.fromString(value));
        } catch (final IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("External audio id must be a UUID");
        }
    }

    @Override
    @SuppressWarnings("NullableProblems") // never null; the project uses no @NotNull annotations
    public String toString() {
        return value.toString();
    }
}
