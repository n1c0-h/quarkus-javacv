package com.example.audio.adapters.output.s3.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;

public class S3ClientProducer {

    /**
     * The builder comes configured by the extension. Multipart uploads are added: they accept bodies of unknown length,
     * and every upload consumes its own body independently of the others.
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("CdiInjectionPointsInspection") // the builder is a synthetic bean of the extension
    S3AsyncClient s3AsyncClient(final S3AsyncClientBuilder builder) {
        return builder.multipartEnabled(true).build();
    }
}
