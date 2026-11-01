package com.example.audio.domain.ports.output;

import java.util.Optional;

import com.example.audio.domain.exceptions.AudioAlreadySynchronizedException;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.ExternalAudioId;

import io.smallrye.mutiny.Uni;

public interface AudioRepository {

    Uni<Optional<Audio>> findById(AudioId id);

    Uni<Boolean> existsByExternalId(ExternalAudioId externalId);

    /** Inserts the audio and its variants atomically; fails with {@link AudioAlreadySynchronizedException} on duplicates. */
    Uni<Void> insert(Audio audio);

    Uni<Void> update(Audio audio);

    Uni<Boolean> delete(AudioId id);
}
