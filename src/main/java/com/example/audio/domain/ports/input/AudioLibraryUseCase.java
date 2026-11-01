package com.example.audio.domain.ports.input;

import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.ExternalAudioId;

import io.smallrye.mutiny.Uni;

/** The audios of the service: copied from the external catalog, then managed here. */
public interface AudioLibraryUseCase {

    /** Copies an external audio into the service: variants in storage, metadata in the database. */
    Uni<Audio> synchronize(ExternalAudioId id);

    Uni<Audio> get(AudioId id);

    Uni<Audio> rename(AudioId id, String name);

    /** Removes the audio and all its stored variants. */
    Uni<Void> delete(AudioId id);
}
