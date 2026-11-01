package com.example.audio.domain.models;

import java.util.UUID;

import com.example.audio.domain.exceptions.InvalidRequestException;

import lombok.NonNull;

/** Public identifier of an audio, used in the API and in storage keys. */
public record AudioId(@NonNull UUID value) {

    public static AudioId of(final UUID value) {
        return new AudioId(value);
    }

    public static AudioId newId() {
        return of(UUID.randomUUID());
    }

    public static AudioId parse(final String value) {
        try {
            return of(UUID.fromString(value));
        } catch (final IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("Audio id must be a UUID");
        }
    }

    @Override
    @SuppressWarnings("NullableProblems") // never null; the project uses no @NotNull annotations
    public String toString() {
        return value.toString();
    }
}
