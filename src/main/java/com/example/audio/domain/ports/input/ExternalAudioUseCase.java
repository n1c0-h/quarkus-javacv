package com.example.audio.domain.ports.input;

import com.example.audio.domain.models.AudioContent;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioId;

import io.smallrye.mutiny.Uni;

/** Read access to the external audio catalog. */
public interface ExternalAudioUseCase {

    Uni<ExternalAudio> getMetadata(ExternalAudioId id);

    /** Blocking. */
    AudioContent download(ExternalAudioId id, AudioFormat format);
}
