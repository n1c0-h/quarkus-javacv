package com.example.audio.domain.services;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.example.audio.domain.models.AudioFormat;

import lombok.Builder;
import lombok.NonNull;

/**
 * What a synchronization fetches, stores and transcodes.
 *
 * @param stored formats stored exactly as downloaded
 * @param transcodings target format → source format it is transcoded from
 * @param durationSource format decoded to measure the duration
 */
@Builder
public record SyncPlan(
        @NonNull Set<AudioFormat> stored,
        @NonNull Map<AudioFormat, AudioFormat> transcodings,
        @NonNull AudioFormat durationSource) {

    /** Every format that must be downloaded from the external service. */
    public Set<AudioFormat> fetched() {
        final Set<AudioFormat> result = EnumSet.noneOf(AudioFormat.class);
        result.addAll(stored);
        result.addAll(transcodings.values());
        result.add(durationSource);
        return result;
    }
}
