package com.example.audio.adapters.output.s3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.apache.commons.io.output.ProxyOutputStream;

import com.example.audio.adapters.output.s3.config.S3ClientProducer;
import com.example.audio.adapters.output.s3.config.StorageConfig;
import com.example.audio.domain.exceptions.DomainException;
import com.example.audio.domain.exceptions.InfrastructureException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.ports.output.AudioStorage;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.BlockingOutputStreamAsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link AudioStorage} on the S3 asynchronous client, through the SDK blocking stream bodies. A body of unknown length
 * is uploaded in parts by the client itself (see {@link S3ClientProducer}).
 */
@Slf4j
@ApplicationScoped
public class S3AudioStorage implements AudioStorage {

    private final S3AsyncClient s3;
    private final StorageConfig config;

    S3AudioStorage(final S3AsyncClient s3, final StorageConfig config) {
        this.s3 = s3;
        this.config = config;
    }

    @Override
    public OutputStream upload(final String key, final AudioFormat format, final OptionalLong length) {
        // writes block until the client has sent the previous bytes
        final BlockingOutputStreamAsyncRequestBody body = BlockingOutputStreamAsyncRequestBody.builder()
                .contentLength(length.isPresent() ? length.getAsLong() : null)
                .subscribeTimeout(config.uploadStartTimeout())
                .build();
        final CompletableFuture<PutObjectResponse> response = s3.putObject(b -> {
            b.bucket(config.bucket()).key(key).contentType(format.getMimeType());
            length.ifPresent(b::contentLength);
        }, body);
        // the SDK stream is only opened on the first write: opening it waits for the client to start the request, which
        // must not keep the caller from feeding its other uploads
        return new ProxyOutputStream(OutputStream.nullOutputStream()) {
            private boolean opened;

            @Override
            protected void beforeWrite(final int n) {
                open();
            }

            private void open() {
                if (!opened) {
                    setReference(body.outputStream());
                    opened = true;
                }
            }

            @Override
            public void close() throws IOException {
                open();
                super.close();
                try {
                    response.join();
                } catch (CompletionException e) {
                    throw translate(e);
                }
            }
        };
    }

    @Override
    public InputStream download(final String key) {
        try {
            return s3.getObject(b -> b.bucket(config.bucket()).key(key), AsyncResponseTransformer.toBlockingInputStream()).join();
        } catch (CompletionException e) {
            throw translate(e);
        }
    }

    @Override
    public Uni<Boolean> exists(final String key) {
        return Uni.createFrom().completionStage(() -> s3.headObject(b -> b.bucket(config.bucket()).key(key)))
                .map(head -> true)
                .onFailure(S3AudioStorage::isNotFound).recoverWithItem(false)
                .onFailure().transform(S3AudioStorage::translate);
    }

    @Override
    public Uni<Void> delete(final Collection<String> keys) {
        if (keys.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        final List<ObjectIdentifier> objects = keys.stream().map(key -> ObjectIdentifier.builder().key(key).build()).toList();
        return Uni.createFrom()
                .completionStage(() -> s3.deleteObjects(b -> b.bucket(config.bucket()).delete(d -> d.objects(objects).quiet(true))))
                .replaceWithVoid()
                .onFailure().transform(S3AudioStorage::translate);
    }

    private static boolean isNotFound(final Throwable t) {
        final Throwable cause = unwrap(t);
        return cause instanceof NoSuchKeyException || cause instanceof S3Exception e && e.statusCode() == 404;
    }

    private static RuntimeException translate(final Throwable t) {
        final Throwable cause = unwrap(t);
        if (cause instanceof DomainException domain) {
            return domain;
        }
        log.error("Storage operation failed", cause);
        return new InfrastructureException("The audio storage is unavailable", cause);
    }

    private static Throwable unwrap(final Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
