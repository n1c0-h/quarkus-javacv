package com.example.audio.application.usecases;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.audio.application.config.ConcatenationConfig;
import com.example.audio.domain.exceptions.AudioNotFoundException;
import com.example.audio.domain.exceptions.InvalidRequestException;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioContent;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.AudioVariant;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.models.FormatSelector;
import com.example.audio.domain.models.Md5Hash;
import com.example.audio.domain.models.SoundName;
import com.example.audio.domain.models.StorageKeys;
import com.example.audio.domain.models.VariantOrigin;

import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

class AudioServicesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String EXTERNAL_ID = "0b0e6f2e-7c55-4c1f-9d1e-3a8a3f6e2a11";

    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-31T10:15:30Z");

    private final Fakes.Repository repository = new Fakes.Repository();
    private final Fakes.Storage storage = new Fakes.Storage();
    private final Fakes.Processor processor = new Fakes.Processor();
    private Audio audio;

    @BeforeEach
    void storeAudio() {
        final AudioId id = AudioId.newId();
        audio = Audio.builder().id(id).externalId(ExternalAudioId.parse(EXTERNAL_ID)).name("Poem").duration(Duration.ofSeconds(35))
                .variant(variant(id, AudioFormat.MP3, "mp3-input"))
                .variant(variant(id, AudioFormat.AMR_NB, "amr-nb-input"))
                .createdAt(CREATED).updatedAt(CREATED)
                .build();
        repository.audios.put(id, audio);
    }

    private AudioVariant variant(final AudioId id, final AudioFormat format, final String content) {
        final String key = StorageKeys.variant(id, format);
        storage.objects.put(key, Fakes.bytes(content));
        return AudioVariant.builder().format(format).origin(VariantOrigin.DOWNLOADED).storageKey(key)
                .size(content.length()).md5(Md5Hash.of(new byte[16])).build();
    }

    @Nested
    class Download {

        private final AudioContentService service = new AudioContentService(repository, storage, processor, Map::of);

        @Test
        void streamsTheStoredVariantWithItsSize() {
            final AudioContent content = service.download(audio.id(), FormatSelector.AMR);

            Assertions.assertThat(content.format()).isEqualTo(AudioFormat.AMR_NB);
            Assertions.assertThat(content.knownLength()).hasValue("amr-nb-input".length());
            Assertions.assertThat(text(content)).isEqualTo("amr-nb-input");
        }

        @Test
        void failsForAVariantThatDoesNotExist() {
            final AudioId id = audio.id();

            Assertions.assertThatThrownBy(() -> service.download(id, FormatSelector.AMR_WB))
                    .isInstanceOf(AudioNotFoundException.class);
        }

        @Test
        void failsForAnUnknownAudio() {
            final AudioId unknown = AudioId.newId();

            Assertions.assertThatThrownBy(() -> service.download(unknown, FormatSelector.MP3))
                    .isInstanceOf(AudioNotFoundException.class);
        }
    }

    @Nested
    class Concatenate {

        private static final ConcatenationConfig CONFIG = () -> Map.of(
                "around", List.of("a", "INPUT", "b"),
                "repeated", List.of("a", "INPUT", "b", "a"));

        private final AudioContentService service = new AudioContentService(repository, storage, processor, CONFIG);

        @Test
        void playsFixedSoundsAndInputInTemplateOrderInTheInputFormat() {
            fixedSound("a", AudioFormat.AMR_NB);
            fixedSound("b", AudioFormat.AMR_NB);

            final AudioContent content = service.concatenate(audio.id(), FormatSelector.AMR, "repeated");

            Assertions.assertThat(content.format()).isEqualTo(AudioFormat.AMR_NB);
            Assertions.assertThat(text(content)).isEqualTo("a.amr-nb|amr-nb-inputb.amr-nb|a.amr-nb|");
            Assertions.assertThat(processor.calls).containsExactly("concatenate to amr-nb");
        }

        @Test
        void playsAnUploadedInputInItsOwnFormat() {
            fixedSound("a", AudioFormat.MP3);
            fixedSound("b", AudioFormat.MP3);
            final byte[] upload = Fakes.bytes("ID3 uploaded mp3|");

            final AudioContent content = service.concatenate(new ByteArrayInputStream(upload), AudioFormat.MP3, "around");

            Assertions.assertThat(content.format()).isEqualTo(AudioFormat.MP3);
            Assertions.assertThat(text(content)).isEqualTo("a.mp3|ID3 uploaded mp3|b.mp3|");
        }

        @Test
        void checksEveryFixedSoundBeforeStreaming() {
            fixedSound("a", AudioFormat.MP3);
            // "b" exists, but not in the format of the input
            fixedSound("b", AudioFormat.AMR_WB);
            final AudioId id = audio.id();

            Assertions.assertThatThrownBy(() -> service.concatenate(id, FormatSelector.MP3, "around"))
                    .isInstanceOf(AudioNotFoundException.class).hasMessageContaining("'b'");

            Assertions.assertThat(processor.calls).isEmpty();
        }

        @Test
        void rejectsAnUnknownTemplate() {
            final AudioId id = audio.id();

            Assertions.assertThatThrownBy(() -> service.concatenate(id, FormatSelector.MP3, "missing"))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("'missing'");
        }

        @Test
        void rejectsAnUploadInAFormatWithoutFixedSounds() {
            final ByteArrayInputStream upload = new ByteArrayInputStream(new byte[16]);

            Assertions.assertThatThrownBy(() -> service.concatenate(upload, AudioFormat.WAV, "around"))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        void refusesAnInvalidConfiguredTemplate() {
            final ConcatenationConfig twoInputs = () -> Map.of("broken", List.of("INPUT", "INPUT"));

            Assertions.assertThatThrownBy(() -> new AudioContentService(repository, storage, processor, twoInputs))
                    .isInstanceOf(InvalidRequestException.class);
        }

        private void fixedSound(final String name, final AudioFormat format) {
            storage.objects.put(StorageKeys.fixedSound(SoundName.of(name), format), Fakes.bytes(name + "." + format.getCode() + "|"));
        }
    }

    @Nested
    class Rename {

        private final AudioLibraryService service = new AudioLibraryService(new Fakes.ExternalCatalog(), repository, storage, processor,
                Clock.fixed(NOW, ZoneOffset.UTC));

        @Test
        void renamesAndTouchesTheUpdateDate() {
            final Audio renamed = service.rename(audio.id(), "  New name ")
                    .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem(TIMEOUT).getItem();

            Assertions.assertThat(renamed.name()).isEqualTo("New name");
            Assertions.assertThat(renamed.updatedAt()).isEqualTo(NOW);
            Assertions.assertThat(renamed.createdAt()).isEqualTo(CREATED);
            Assertions.assertThat(repository.audios).containsEntry(audio.id(), renamed);
        }

        @Test
        @SuppressWarnings("ReactiveStreamsUnusedPublisher") // the name is checked before the Uni is even built
        void rejectsControlCharactersBeforeAnyLookup() {
            final AudioId id = audio.id();

            Assertions.assertThatThrownBy(() -> service.rename(id, "line\r\nbreak"))
                    .isInstanceOf(InvalidRequestException.class);
            Assertions.assertThat(repository.calls).isEmpty();
        }
    }

    @Nested
    class Delete {

        private final AudioLibraryService service = new AudioLibraryService(new Fakes.ExternalCatalog(), repository, storage, processor,
                Clock.systemUTC());

        @Test
        void deletesTheRowThenEveryVariant() {
            service.delete(audio.id()).subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem(TIMEOUT);

            Assertions.assertThat(repository.audios).isEmpty();
            Assertions.assertThat(storage.deleted).containsExactlyInAnyOrderElementsOf(
                    audio.variants().stream().map(AudioVariant::storageKey).toList());
        }

        @Test
        void succeedsWhenOnlyTheContentRemovalFails() {
            storage.failDeletes = true;

            service.delete(audio.id()).subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem(TIMEOUT);

            Assertions.assertThat(repository.audios).isEmpty();
        }
    }

    @Nested
    class DownloadExternal {

        private final Fakes.ExternalCatalog catalog = new Fakes.ExternalCatalog();
        private final ExternalAudioService service = new ExternalAudioService(catalog);

        @Test
        void announcesTheSizeGivenByTheCatalog() {
            catalog.add(EXTERNAL_ID, "Poem", Map.of(AudioFormat.WAV, Fakes.bytes("wav-content")));

            final AudioContent content = service.download(ExternalAudioId.parse(EXTERNAL_ID), AudioFormat.WAV);

            Assertions.assertThat(content.knownLength()).hasValue("wav-content".length());
            Assertions.assertThat(catalog.downloads).isEmpty();
            Assertions.assertThat(text(content)).isEqualTo("wav-content");
        }

        @Test
        void failsForAFormatTheCatalogDoesNotOffer() {
            catalog.add(EXTERNAL_ID, "Poem", Map.of(AudioFormat.WAV, Fakes.bytes("wav")));

            final ExternalAudioId id = ExternalAudioId.parse(EXTERNAL_ID);

            Assertions.assertThatThrownBy(() -> service.download(id, AudioFormat.MP3))
                    .isInstanceOf(AudioNotFoundException.class);
        }
    }

    private static String text(final AudioContent content) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            content.writeTo(output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
