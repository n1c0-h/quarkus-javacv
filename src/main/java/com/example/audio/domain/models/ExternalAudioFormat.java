package com.example.audio.domain.models;

import lombok.NonNull;

public record ExternalAudioFormat(@NonNull AudioFormat format, long size) {

    public static ExternalAudioFormat of(final AudioFormat format, final long size) {
        return new ExternalAudioFormat(format, size);
    }
}
