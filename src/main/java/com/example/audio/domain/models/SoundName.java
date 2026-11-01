package com.example.audio.domain.models;

import java.util.regex.Pattern;

import com.example.audio.domain.exceptions.InvalidRequestException;

/**
 * Name of a fixed sound. The charset is restricted because it becomes part of a storage key.
 */
public record SoundName(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9-]{1,64}$");

    public SoundName {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidRequestException("Fixed sound names must match " + FORMAT.pattern());
        }
    }

    public static SoundName of(final String value) {
        return new SoundName(value);
    }

    @Override
    @SuppressWarnings("NullableProblems") // never null; the project uses no @NotNull annotations
    public String toString() {
        return value;
    }
}
