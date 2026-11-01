package com.example.audio.adapters.input.rest;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.Api;
import com.example.audio.support.AudioFixtures;
import com.example.audio.support.ExternalCatalogStub;
import com.example.audio.support.SyncCases;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/** Synchronization end to end, for every combination of formats the external service can offer. */
@QuarkusTest
@ConnectWireMock
class SynchronizationTest {

    private static final Duration TOLERANCE = Duration.ofMillis(150);
    private static final AtomicInteger SAMPLE = new AtomicInteger();

    WireMock wiremock;

    static Stream<SyncCases.Case> cases() {
        return SyncCases.all();
    }

    @ParameterizedTest(name = "offered {0}")
    @MethodSource("cases")
    void storesMp3AndAmrWithMetadata(final SyncCases.Case expected) {
        // a different voice for each case
        final String sample = AudioFixtures.SAMPLES.get(SAMPLE.getAndIncrement() % AudioFixtures.SAMPLES.size());
        final String externalId = UUID.randomUUID().toString();
        new ExternalCatalogStub(wiremock).audio(externalId, sample, expected.offered());

        final JsonPath audio = Api.synchronize(externalId);

        final Duration sourceDuration = AudioFixtures.duration(AudioFixtures.sample(sample, expected.durationSource()));
        Assertions.assertThat(Api.duration(audio)).isCloseTo(sourceDuration, TOLERANCE);
        Assertions.assertThat(audio.getString("externalId")).isEqualTo(externalId);
        Assertions.assertThat(audio.getMap("variants[0]")).doesNotContainKey("storageKey");

        List<Map<String, Object>> variants = audio.getList("variants");
        Assertions.assertThat(variants).extracting(v -> v.get("format") + ":" + v.get("origin"))
                .containsExactlyInAnyOrderElementsOf(expectedVariants(expected));

        for (final Map<String, Object> variant : variants) {
            final AudioFormat format = AudioFormat.fromCode((String) variant.get("format")).orElseThrow();
            final byte[] content = Api.content(audio.getString("id"), format.getCode());
            Assertions.assertThat(Api.md5(content)).isEqualTo(variant.get("md5"));
            Assertions.assertThat((long) content.length).isEqualTo(((Number) variant.get("size")).longValue());
            if ("DOWNLOADED".equals(variant.get("origin"))) {
                Assertions.assertThat(content).isEqualTo(AudioFixtures.bytes(AudioFixtures.sample(sample, format)));
            } else {
                Assertions.assertThat(format.matchesSignature(Arrays.copyOf(content, AudioFormat.SIGNATURE_LENGTH))).isTrue();
                Assertions.assertThat(AudioFixtures.duration(content)).isCloseTo(sourceDuration, TOLERANCE.multipliedBy(2));
            }
        }
    }

    private static List<String> expectedVariants(final SyncCases.Case expected) {
        return Stream.concat(
                expected.stored().stream().map(f -> f.getCode() + ":DOWNLOADED"),
                expected.transcodings().keySet().stream().map(f -> f.getCode() + ":TRANSCODED"))
                .toList();
    }
}
