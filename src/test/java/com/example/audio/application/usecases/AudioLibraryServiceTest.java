package com.example.audio.application.usecases;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.audio.domain.exceptions.AudioAlreadySynchronizedException;
import com.example.audio.domain.exceptions.AudioProcessingException;
import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioVariant;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.models.VariantOrigin;
import com.example.audio.support.SyncCases;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

class AudioLibraryServiceTest {

    private static final String EXTERNAL_ID = "0b0e6f2e-7c55-4c1f-9d1e-3a8a3f6e2a11";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final Instant NOW = Instant.parse("2026-10-31T10:15:30.123456Z");

    private final Fakes.ExternalCatalog catalog = new Fakes.ExternalCatalog();
    private final Fakes.Repository repository = new Fakes.Repository();
    private final Fakes.Storage storage = new Fakes.Storage();
    private final Fakes.Processor processor = new Fakes.Processor();
    private final AudioLibraryService service = new AudioLibraryService(catalog, repository, storage, processor,
            Clock.fixed(NOW, ZoneOffset.UTC));

    static Stream<SyncCases.Case> cases() {
        return SyncCases.all();
    }

    @ParameterizedTest(name = "offered {0}")
    @MethodSource("cases")
    void storesTheExpectedVariantsAndDownloadsEachFormatOnce(final SyncCases.Case expected) {
        final Map<AudioFormat, byte[]> offered = new EnumMap<>(AudioFormat.class);
        expected.offered().forEach(format -> offered.put(format, Fakes.bytes("content-" + format.getCode())));
        catalog.add(EXTERNAL_ID, "Poem", offered);

        final Audio audio = synchronize(service);

        Assertions.assertThat(audio.formats()).containsExactlyInAnyOrderElementsOf(expected.resultFormats());
        for (final AudioVariant variant : audio.variants()) {
            final byte[] stored = storage.objects.get(variant.storageKey());
            Assertions.assertThat(variant.md5().hex()).isEqualTo(md5(stored));
            Assertions.assertThat(variant.size()).isEqualTo(stored.length);
            if (expected.stored().contains(variant.format())) {
                Assertions.assertThat(variant.origin()).isEqualTo(VariantOrigin.DOWNLOADED);
                Assertions.assertThat(stored).isEqualTo(offered.get(variant.format()));
            } else {
                final AudioFormat source = expected.transcodings().get(variant.format());
                Assertions.assertThat(variant.origin()).isEqualTo(VariantOrigin.TRANSCODED);
                Assertions.assertThat(new String(stored, StandardCharsets.UTF_8))
                        .isEqualTo(variant.format().getCode() + "(content-" + source.getCode() + ")");
            }
        }
        // the fake probe measures 1 ms per byte of the duration source
        Assertions.assertThat(audio.duration()).isEqualTo(Duration.ofMillis(offered.get(expected.durationSource()).length));
        final EnumSet<AudioFormat> used = EnumSet.copyOf(expected.offered());
        used.retainAll(Stream.concat(expected.stored().stream(), expected.transcodings().values().stream()).toList());
        Assertions.assertThat(catalog.downloads.keySet()).isEqualTo(used);
        Assertions.assertThat(catalog.downloads).allSatisfy((format, count) -> Assertions.assertThat(count.get()).as(format.getCode()).isEqualTo(1));
        Assertions.assertThat(repository.audios).containsValue(audio);
    }

    @Test
    void keepsTheExternalIdentityAndTimestamps() {
        catalog.add(EXTERNAL_ID, "Dust of Snow", Map.of(AudioFormat.MP3, Fakes.bytes("mp3"), AudioFormat.AMR_WB, Fakes.bytes("amr")));

        final Audio audio = synchronize(service);

        Assertions.assertThat(audio.externalId()).isEqualTo(ExternalAudioId.parse(EXTERNAL_ID));
        Assertions.assertThat(audio.name()).isEqualTo("Dust of Snow");
        Assertions.assertThat(audio.createdAt()).isEqualTo(NOW);
        Assertions.assertThat(audio.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void fallsBackToTheIdWhenTheExternalNameIsNotAcceptable() {
        catalog.add(EXTERNAL_ID, "evil\nname", Map.of(AudioFormat.MP3, Fakes.bytes("mp3"), AudioFormat.AMR_WB, Fakes.bytes("amr")));

        final Audio audio = synchronize(service);

        Assertions.assertThat(audio.name()).isEqualTo(EXTERNAL_ID);
    }

    @Test
    void refusesAnAudioAlreadySynchronizedWithoutDownloadingIt() {
        catalog.add(EXTERNAL_ID, "Poem", Map.of(AudioFormat.MP3, Fakes.bytes("mp3"), AudioFormat.AMR_WB, Fakes.bytes("amr")));
        synchronize(service);
        catalog.downloads.clear();

        Assertions.assertThat(failure(service)).isInstanceOf(AudioAlreadySynchronizedException.class);

        Assertions.assertThat(catalog.downloads).isEmpty();
    }

    @Test
    void removesUploadedContentWhenATranscodingFails() {
        catalog.add(EXTERNAL_ID, "Poem", Map.of(AudioFormat.WAV, Fakes.bytes("wav")));
        processor.failingTarget = AudioFormat.AMR_WB;

        Assertions.assertThat(failure(service)).isInstanceOf(AudioProcessingException.class);

        Assertions.assertThat(storage.objects).isEmpty();
        Assertions.assertThat(storage.deleted).hasSize(2).allMatch(key -> key.startsWith("audios/"));
        Assertions.assertThat(repository.audios).isEmpty();
    }

    @Test
    void readsEveryOriginalThroughTheProcessorEvenWithoutTranscoding() {
        catalog.add(EXTERNAL_ID, "Poem", Map.of(AudioFormat.MP3, Fakes.bytes("mp3"), AudioFormat.AMR_NB, Fakes.bytes("amr")));

        synchronize(service);

        Assertions.assertThat(processor.calls).containsExactlyInAnyOrder("read mp3", "read amr-nb");
    }

    @Test
    void storesNothingWhenAnOriginalIsNotValidAudio() {
        catalog.add(EXTERNAL_ID, "Poem", Map.of(AudioFormat.MP3, Fakes.bytes("mp3"), AudioFormat.AMR_NB, Fakes.bytes("not amr")));
        processor.invalidSource = AudioFormat.AMR_NB;

        Assertions.assertThat(failure(service)).isInstanceOf(InvalidAudioException.class);

        Assertions.assertThat(storage.objects).isEmpty();
        Assertions.assertThat(repository.audios).isEmpty();
    }

    @Test
    void removesUploadedContentWhenTheDatabaseRejectsTheAudio() {
        catalog.add(EXTERNAL_ID, "Poem", Map.of(AudioFormat.MP3, Fakes.bytes("mp3"), AudioFormat.AMR_NB, Fakes.bytes("amr")));
        // a concurrent synchronization wins between the existence check and the insert
        final Fakes.Repository racing = new Fakes.Repository() {
            @Override
            public Uni<Void> insert(final Audio audio) {
                return Uni.createFrom().failure(new AudioAlreadySynchronizedException("duplicate"));
            }
        };
        final AudioLibraryService racingService = new AudioLibraryService(catalog, racing, storage, processor, Clock.fixed(NOW, ZoneOffset.UTC));

        Assertions.assertThat(failure(racingService)).isInstanceOf(AudioAlreadySynchronizedException.class);

        Assertions.assertThat(storage.objects).isEmpty();
    }

    private static Audio synchronize(final AudioLibraryService service) {
        return service.synchronize(ExternalAudioId.parse(EXTERNAL_ID)).subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(TIMEOUT).getItem();
    }

    private static Throwable failure(final AudioLibraryService service) {
        return service.synchronize(ExternalAudioId.parse(EXTERNAL_ID)).subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure(TIMEOUT).getFailure();
    }


    private static String md5(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
