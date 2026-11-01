package com.example.audio.application.usecases;

import java.io.InputStream;

import com.example.audio.domain.exceptions.AudioNotFoundException;
import com.example.audio.domain.models.AudioContent;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.ports.input.ExternalAudioUseCase;
import com.example.audio.domain.ports.output.ExternalAudioProvider;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class ExternalAudioService implements ExternalAudioUseCase {

    private final ExternalAudioProvider externalAudioProvider;

    @Override
    public Uni<ExternalAudio> getMetadata(final ExternalAudioId id) {
        return externalAudioProvider.getMetadata(id);
    }

    @Override
    public AudioContent download(final ExternalAudioId id, final AudioFormat format) {
        final ExternalAudio metadata = externalAudioProvider.getMetadata(id).await().indefinitely();
        final long size = metadata.sizeOf(format)
                .orElseThrow(() -> new AudioNotFoundException("The external audio is not available in " + format.getCode()));
        return AudioContent.of(format, size, output -> {
            try (InputStream input = externalAudioProvider.download(id, format)) {
                input.transferTo(output);
            }
        });
    }
}
