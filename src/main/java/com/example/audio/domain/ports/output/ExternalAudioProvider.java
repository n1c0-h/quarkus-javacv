package com.example.audio.domain.ports.output;

import java.io.InputStream;

import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioId;

import io.smallrye.mutiny.Uni;

/** External audio catalog. */
public interface ExternalAudioProvider {

    Uni<ExternalAudio> getMetadata(ExternalAudioId id);

    /** Opens one format of the audio; the bytes arrive while the stream is read. Blocking. */
    InputStream download(ExternalAudioId id, AudioFormat format);
}
