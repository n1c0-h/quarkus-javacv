package com.example.audio.domain.models;

import lombok.Builder;
import lombok.NonNull;

/** One stored encoding of an audio. */
@Builder
public record AudioVariant(
        @NonNull AudioFormat format,
        @NonNull VariantOrigin origin,
        @NonNull String storageKey,
        long size,
        @NonNull Md5Hash md5) {
}
