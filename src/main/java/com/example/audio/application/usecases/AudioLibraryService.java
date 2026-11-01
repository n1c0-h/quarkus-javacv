package com.example.audio.application.usecases;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.io.output.CountingOutputStream;

import com.example.audio.domain.exceptions.AudioAlreadySynchronizedException;
import com.example.audio.domain.exceptions.AudioNotFoundException;
import com.example.audio.domain.exceptions.AudioProcessingException;
import com.example.audio.domain.exceptions.DomainException;
import com.example.audio.domain.exceptions.InfrastructureException;
import com.example.audio.domain.exceptions.InvalidRequestException;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.AudioVariant;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.models.Md5Hash;
import com.example.audio.domain.models.StorageKeys;
import com.example.audio.domain.models.VariantOrigin;
import com.example.audio.domain.ports.input.AudioLibraryUseCase;
import com.example.audio.domain.ports.output.AudioProcessor;
import com.example.audio.domain.ports.output.AudioRepository;
import com.example.audio.domain.ports.output.AudioStorage;
import com.example.audio.domain.ports.output.ExternalAudioProvider;
import com.example.audio.domain.services.SyncPlan;
import com.example.audio.domain.services.VariantPolicy;

import io.smallrye.mutiny.CompositeException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Synchronization: the checks, the external metadata and the database insert are non-blocking; only the copies of the
 * external formats take a worker thread, one each, as they stream. While FFmpeg reads a download, every chunk is also
 * copied to the storage upload of the original. FFmpeg encodes the missing formats on the fly, each one straight
 * into its own storage upload.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class AudioLibraryService implements AudioLibraryUseCase {

    private final ExternalAudioProvider externalAudioProvider;
    private final AudioRepository audioRepository;
    private final AudioStorage audioStorage;
    private final AudioProcessor audioProcessor;
    private final Clock clock;
    private final VariantPolicy variantPolicy = new VariantPolicy();

    /** What the copy of one external format produced. */
    private record Copy(List<AudioVariant> variants, Duration duration) {
    }

    @Override
    public Uni<Audio> synchronize(final ExternalAudioId id) {
        return audioRepository.existsByExternalId(id)
                .chain(exists -> Boolean.TRUE.equals(exists)
                        ? Uni.createFrom().failure(new AudioAlreadySynchronizedException("The external audio is already synchronized"))
                        : externalAudioProvider.getMetadata(id))
                .chain(this::store);
    }

    private Uni<Audio> store(final ExternalAudio metadata) {
        final SyncPlan plan = variantPolicy.plan(metadata.availableFormats());
        final AudioId audioId = AudioId.newId();
        return Uni.join()
                .all(plan.fetched().stream()
                        .map(format -> Uni.createFrom().item(() -> copy(metadata, audioId, format, plan))
                                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                        .toList())
                // every copy is finished before a failure is reported, so the cleanup misses no upload
                .andCollectFailures()
                .map(copies -> newAudio(audioId, metadata, copies))
                .call(audioRepository::insert)
                .onFailure().call(e -> cleanUp(audioId, plan))
                .onFailure().transform(AudioLibraryService::unwrap);
    }

    @Override
    public Uni<Audio> get(final AudioId id) {
        return find(audioRepository, id);
    }

    @Override
    public Uni<Audio> rename(final AudioId id, final String name) {
        final String validName = Audio.validName(name);
        return find(audioRepository, id)
                .map(audio -> audio.rename(validName, clock.instant()))
                .call(audioRepository::update);
    }

    /** The row goes first: an orphan object is harmless, a row pointing to deleted content is not. */
    @Override
    public Uni<Void> delete(final AudioId id) {
        return find(audioRepository, id)
                .call(audio -> audioRepository.delete(id))
                .chain(audio -> audioStorage.delete(audio.variants().stream().map(AudioVariant::storageKey).toList())
                        .onFailure().invoke(e -> log.warn("Content of deleted audio {} could not be removed", id, e))
                        .onFailure().recoverWithNull());
    }

    static Uni<Audio> find(final AudioRepository repository, final AudioId id) {
        return repository.findById(id).map(found -> found.orElseThrow(() -> new AudioNotFoundException("Audio not found")));
    }

    @SuppressWarnings("java:S9391") // completing an upload throws a checked IOException: a loop is clearer than a stream
    private Copy copy(final ExternalAudio metadata, final AudioId audioId, final AudioFormat format, final SyncPlan plan) {
        final Tracked stored = plan.stored().contains(format) ? new Tracked(audioId, format, metadata.sizeOf(format)) : null;
        final Map<AudioFormat, Tracked> transcoded = new EnumMap<>(AudioFormat.class);
        plan.transcodings().forEach((target, source) -> {
            if (source == format) {
                transcoded.put(target, new Tracked(audioId, target, OptionalLong.empty()));
            }
        });
        try (InputStream download = externalAudioProvider.download(metadata.id(), format);
                InputStream input = stored == null ? download : new TeeInputStream(download, stored.output)) {
            final Map<AudioFormat, OutputStream> outputs = new EnumMap<>(AudioFormat.class);
            transcoded.forEach((target, tracked) -> outputs.put(target, tracked.output));
            // the processor reads its input to the end, copying the original on the way; without output it only
            // demuxes it, which is enough to reject an original that is not valid audio before it is stored
            final Duration duration = audioProcessor.process(input, format, outputs);
            final List<AudioVariant> variants = new ArrayList<>();
            if (stored != null) {
                variants.add(stored.complete(VariantOrigin.DOWNLOADED));
            }
            for (final Tracked tracked : transcoded.values()) {
                variants.add(tracked.complete(VariantOrigin.TRANSCODED));
            }
            return new Copy(variants, plan.durationSource() == format ? duration : null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (stored != null) {
                stored.closeQuietly();
            }
            transcoded.values().forEach(Tracked::closeQuietly);
        }
    }

    private Audio newAudio(final AudioId id, final ExternalAudio metadata, final List<Copy> copies) {
        final Instant now = clock.instant();
        return Audio.builder()
                .id(id)
                .externalId(metadata.id())
                .name(nameOf(metadata))
                .duration(copies.stream().map(Copy::duration).filter(Objects::nonNull).findFirst().orElseThrow())
                .variants(copies.stream().flatMap(copy -> copy.variants().stream()).toList())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Never fails: the failure of the synchronization is the one reported. */
    private Uni<Void> cleanUp(final AudioId id, final SyncPlan plan) {
        final List<String> keys = new ArrayList<>();
        plan.stored().forEach(format -> keys.add(StorageKeys.variant(id, format)));
        plan.transcodings().keySet().forEach(format -> keys.add(StorageKeys.variant(id, format)));
        return audioStorage.delete(keys)
                .onFailure().invoke(e -> log.warn("Cleanup of audio {} failed", id, e))
                .onFailure().recoverWithNull();
    }

    /** The external name is untrusted: fall back to the id when it is not acceptable. */
    private static String nameOf(final ExternalAudio metadata) {
        try {
            return Audio.validName(metadata.name());
        } catch (InvalidRequestException e) {
            return metadata.id().toString();
        }
    }

    /** Reports the business failure of the parallel copies, if any. */
    private static Throwable unwrap(final Throwable e) {
        final List<Throwable> failures = e instanceof CompositeException composite ? composite.getCauses() : List.of(e);
        return failures.stream()
                .filter(DomainException.class::isInstance)
                .findFirst()
                .orElseGet(() -> new InfrastructureException("The audio could not be copied", failures.getFirst()));
    }

    /** A storage upload whose MD5 and size are computed while the bytes go through. */
    private final class Tracked {

        private final String key;
        private final AudioFormat format;
        private final MessageDigest md5 = md5();
        private final CountingOutputStream counter;
        private final OutputStream output;
        private boolean closed;

        private Tracked(final AudioId audioId, final AudioFormat format, final OptionalLong length) {
            this.key = StorageKeys.variant(audioId, format);
            this.format = format;
            this.counter = new CountingOutputStream(audioStorage.upload(key, format, length));
            this.output = new DigestOutputStream(counter, md5);
        }

        /** Completes the upload and describes the stored variant. */
        private AudioVariant complete(final VariantOrigin origin) throws IOException {
            output.close();
            closed = true;
            return AudioVariant.builder().format(format).origin(origin).storageKey(key)
                    .size(counter.getByteCount()).md5(Md5Hash.of(md5.digest())).build();
        }

        private void closeQuietly() {
            if (!closed) {
                try {
                    output.close();
                } catch (IOException | RuntimeException e) {
                    log.debug("Upload of {} aborted", key, e);
                }
            }
        }
    }

    /** MD5 is the content checksum of the stored variants (integrity, not security), as required. */
    @SuppressWarnings("java:S4790")
    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new AudioProcessingException("MD5 is not available", e);
        }
    }
}
