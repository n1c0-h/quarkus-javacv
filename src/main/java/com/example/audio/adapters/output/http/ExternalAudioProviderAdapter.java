package com.example.audio.adapters.output.http;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.input.ProxyInputStream;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import com.example.audio.domain.exceptions.DomainException;
import com.example.audio.domain.exceptions.ExternalServiceException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.ports.output.ExternalAudioProvider;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ExternalAudioProviderAdapter implements ExternalAudioProvider {

    private final ExternalAudioClient client;

    ExternalAudioProviderAdapter(@RestClient final ExternalAudioClient client) {
        this.client = client;
    }

    @Override
    public Uni<ExternalAudio> getMetadata(final ExternalAudioId id) {
        return client.getMetadata(id.toString())
                .onItem().transformToUni(audio -> id.equals(audio.id())
                        ? Uni.createFrom().item(audio)
                        : Uni.createFrom().failure(new ExternalServiceException("The external audio service answered for another audio")))
                .onFailure().transform(ExternalAudioProviderAdapter::translate);
    }

    @Override
    public InputStream download(final ExternalAudioId id, final AudioFormat format) {
        try {
            return new ProxyInputStream(client.download(id.toString(), format.getCode())) {
                // a failure in the middle of the body is a failure of the external service
                @Override
                protected void handleIOException(final IOException e) {
                    throw translate(e);
                }
            };
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(final Throwable t) {
        if (t instanceof DomainException domain) {
            return domain;
        }
        log.warn("External audio service call failed", t);
        return new ExternalServiceException("The external audio service is unavailable", t);
    }
}
