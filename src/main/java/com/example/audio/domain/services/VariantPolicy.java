package com.example.audio.domain.services;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.audio.domain.exceptions.InvalidRequestException;
import com.example.audio.domain.models.AudioFormat;

/**
 * Decides which variants a synchronized audio gets.
 * <ul>
 * <li>Every available proposed format (mp3, amr-nb, amr-wb) is kept as-is.</li>
 * <li>A missing mp3 is transcoded from the best source: wav, then amr-wb, then amr-nb.</li>
 * <li>A missing amr (neither nb nor wb) is transcoded to amr-wb from wav, then mp3.</li>
 * <li>Wav is never stored.</li>
 * <li>The duration is measured on the most accurate fetched format.</li>
 * </ul>
 */
public final class VariantPolicy {

    private static final List<AudioFormat> MP3_SOURCES = List.of(AudioFormat.WAV, AudioFormat.AMR_WB, AudioFormat.AMR_NB);
    private static final List<AudioFormat> AMR_SOURCES = List.of(AudioFormat.WAV, AudioFormat.MP3);
    /** Most accurate first: uncompressed, then the formats with the finest frame granularity. */
    private static final List<AudioFormat> DURATION_SOURCES = List.of(AudioFormat.WAV, AudioFormat.MP3, AudioFormat.AMR_WB,
            AudioFormat.AMR_NB);

    public SyncPlan plan(final Set<AudioFormat> available) {
        if (available.isEmpty()) {
            throw new InvalidRequestException("The external audio has no supported format");
        }
        final EnumSet<AudioFormat> stored = EnumSet.noneOf(AudioFormat.class);
        available.stream().filter(AudioFormat::isProposed).forEach(stored::add);

        final Map<AudioFormat, AudioFormat> transcodings = new EnumMap<>(AudioFormat.class);
        if (!available.contains(AudioFormat.MP3)) {
            transcodings.put(AudioFormat.MP3, firstAvailable(MP3_SOURCES, available).orElseThrow());
        }
        if (!available.contains(AudioFormat.AMR_NB) && !available.contains(AudioFormat.AMR_WB)) {
            transcodings.put(AudioFormat.AMR_WB, firstAvailable(AMR_SOURCES, available).orElseThrow());
        }

        // measure on a stream that is fetched anyway, never download a format only for its duration
        final EnumSet<AudioFormat> fetched = EnumSet.noneOf(AudioFormat.class);
        fetched.addAll(stored);
        fetched.addAll(transcodings.values());
        return SyncPlan.builder()
                .stored(Collections.unmodifiableSet(stored))
                .transcodings(Collections.unmodifiableMap(transcodings))
                .durationSource(firstAvailable(DURATION_SOURCES, fetched).orElseThrow())
                .build();
    }

    private static Optional<AudioFormat> firstAvailable(final List<AudioFormat> preference, final Set<AudioFormat> available) {
        return preference.stream().filter(available::contains).findFirst();
    }
}
