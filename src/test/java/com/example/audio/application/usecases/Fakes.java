package com.example.audio.application.usecases;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.example.audio.domain.exceptions.AudioAlreadySynchronizedException;
import com.example.audio.domain.exceptions.AudioNotFoundException;
import com.example.audio.domain.exceptions.AudioProcessingException;
import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioFormat;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.ports.output.AudioProcessor;
import com.example.audio.domain.ports.output.AudioRepository;
import com.example.audio.domain.ports.output.AudioStorage;
import com.example.audio.domain.ports.output.ExternalAudioProvider;

import io.smallrye.mutiny.Uni;

/** In-memory port implementations, reading and writing streams like the real adapters. */
final class Fakes {

    private Fakes() {
    }

    static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String text(final byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    static byte[] readAll(final InputStream input) {
        try (input) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static class ExternalCatalog implements ExternalAudioProvider {

        final Map<String, ExternalAudio> audios = new ConcurrentHashMap<>();
        final Map<String, byte[]> contents = new ConcurrentHashMap<>();
        final Map<AudioFormat, AtomicInteger> downloads = new ConcurrentHashMap<>();

        void add(final String id, final String name, final Map<AudioFormat, byte[]> formats) {
            final ExternalAudio.ExternalAudioBuilder audio = ExternalAudio.builder().id(ExternalAudioId.parse(id)).name(name);
            formats.forEach((format, data) -> {
                audio.format(ExternalAudioFormat.of(format, data.length));
                contents.put(id + "/" + format, data);
            });
            audios.put(id, audio.build());
        }

        @Override
        public Uni<ExternalAudio> getMetadata(final ExternalAudioId id) {
            final ExternalAudio audio = audios.get(id.toString());
            return audio == null ? Uni.createFrom().failure(new AudioNotFoundException("not found")) : Uni.createFrom().item(audio);
        }

        @Override
        public InputStream download(final ExternalAudioId id, final AudioFormat format) {
            downloads.computeIfAbsent(format, f -> new AtomicInteger()).incrementAndGet();
            return new ByteArrayInputStream(contents.get(id + "/" + format));
        }
    }

    static class Storage implements AudioStorage {

        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        boolean failDeletes;

        @Override
        public OutputStream upload(final String key, final AudioFormat format, final OptionalLong length) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    objects.put(key, toByteArray());
                }
            };
        }

        @Override
        public InputStream download(final String key) {
            return new ByteArrayInputStream(objects.get(key));
        }

        @Override
        public Uni<Boolean> exists(final String key) {
            return Uni.createFrom().item(objects.containsKey(key));
        }

        @Override
        public Uni<Void> delete(final Collection<String> keys) {
            if (failDeletes) {
                return Uni.createFrom().failure(new IllegalStateException("storage down"));
            }
            deleted.addAll(keys);
            keys.forEach(objects::remove);
            return Uni.createFrom().voidItem();
        }
    }

    /** Durations are the input sizes in ms; outputs are readable markers of what was done. */
    static class Processor implements AudioProcessor {

        final List<String> calls = new CopyOnWriteArrayList<>();
        AudioFormat failingTarget;
        /** Content in this format is rejected as invalid audio. */
        AudioFormat invalidSource;

        @Override
        public Duration process(final InputStream input, final AudioFormat format, final Map<AudioFormat, OutputStream> outputs) {
            final byte[] data = readAll(input);
            calls.add("read " + format.getCode());
            if (format == invalidSource) {
                throw new InvalidAudioException("invalid " + format.getCode());
            }
            outputs.forEach((target, output) -> {
                if (target == failingTarget) {
                    throw new AudioProcessingException("transcoding failed");
                }
                calls.add("transcode " + format.getCode() + " to " + target.getCode());
                write(output, target.getCode() + "(" + text(data) + ")");
            });
            return Duration.ofMillis(data.length);
        }

        @Override
        public void concatenate(final List<Supplier<InputStream>> parts, final int inputIndex, final AudioFormat format,
                final OutputStream output) {
            calls.add("concatenate to " + format.getCode());
            parts.forEach(part -> write(output, text(readAll(part.get()))));
        }

        private static void write(final OutputStream output, final String text) {
            try {
                output.write(bytes(text));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class Repository implements AudioRepository {

        final Map<AudioId, Audio> audios = new ConcurrentHashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public Uni<Optional<Audio>> findById(final AudioId id) {
            return Uni.createFrom().item(Optional.ofNullable(audios.get(id)));
        }

        @Override
        public Uni<Boolean> existsByExternalId(final ExternalAudioId externalId) {
            return Uni.createFrom().item(audios.values().stream().anyMatch(a -> a.externalId().equals(externalId)));
        }

        @Override
        public Uni<Void> insert(final Audio audio) {
            return existsByExternalId(audio.externalId()).chain(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Uni.createFrom().failure(new AudioAlreadySynchronizedException("duplicate"));
                }
                audios.put(audio.id(), audio);
                return Uni.createFrom().voidItem();
            });
        }

        @Override
        public Uni<Void> update(final Audio audio) {
            calls.add("update");
            audios.put(audio.id(), audio);
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> delete(final AudioId id) {
            calls.add("delete");
            return Uni.createFrom().item(audios.remove(id) != null);
        }
    }
}
