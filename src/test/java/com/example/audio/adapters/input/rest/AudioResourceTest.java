package com.example.audio.adapters.input.rest;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.StorageKeys;
import com.example.audio.domain.ports.output.AudioStorage;
import com.example.audio.support.Api;
import com.example.audio.support.AudioFixtures;
import com.example.audio.support.ExternalCatalogStub;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.inject.Inject;
import net.javacrumbs.jsonunit.assertj.JsonAssertions;

@QuarkusTest
@ConnectWireMock
class AudioResourceTest {

    private static final String SAMPLE = "dustofsnow_wt";
    private static final Duration TOLERANCE = Duration.ofMillis(300);

    WireMock wiremock;

    @Inject
    AudioStorage storage;

    private String audioId;
    private String externalId;

    /** An audio offered in every proposed format, so no transcoding happens at synchronization. */
    @BeforeEach
    void synchronizeAudio() {
        externalId = UUID.randomUUID().toString();
        new ExternalCatalogStub(wiremock).audio(externalId, SAMPLE, AudioFixtures.PROPOSED);
        audioId = Api.synchronize(externalId).getString("id");
    }

    @Test
    void exposesMetadataWithoutInternalDetails() {
        final String json = RestAssured.given().get("/audios/{id}", audioId).then().statusCode(200).extract().asString();

        JsonAssertions.assertThatJson(json).isEqualTo("""
                {
                  "id": "%s",
                  "externalId": "%s",
                  "name": "%s",
                  "duration": "${json-unit.regex}PT3\\\\d\\\\.\\\\d+S",
                  "variants": [
                    {"format": "mp3", "origin": "DOWNLOADED", "size": %d, "md5": "%s"},
                    {"format": "amr-nb", "origin": "DOWNLOADED", "size": %d, "md5": "%s"},
                    {"format": "amr-wb", "origin": "DOWNLOADED", "size": %d, "md5": "%s"}
                  ],
                  "createdAt": "${json-unit.any-string}",
                  "updatedAt": "${json-unit.any-string}"
                }""".formatted(audioId, externalId, SAMPLE,
                size(AudioFormat.MP3), md5(AudioFormat.MP3),
                size(AudioFormat.AMR_NB), md5(AudioFormat.AMR_NB),
                size(AudioFormat.AMR_WB), md5(AudioFormat.AMR_WB)));
    }

    @Test
    void streamsTheRequestedVariant() {
        final Response response = RestAssured.given().queryParam("format", "amr").get("/audios/{id}/content", audioId);

        response.then().statusCode(200)
                .header("Content-Type", "audio/amr-wb")
                .header("Content-Disposition", "attachment; filename=\"audio-" + audioId + ".amr\"");
        Assertions.assertThat(response.asByteArray()).isEqualTo(fixture(AudioFormat.AMR_WB));
    }

    /** The templates configured in application.yml, with their parts. */
    static Stream<Arguments> concatenations() {
        return Stream.of(Arguments.of("greeting", "a,INPUT,b"), Arguments.of("intro", "c,INPUT"), Arguments.of("outro", "INPUT,d"))
                .flatMap(template -> Stream.of("mp3", "amr-nb", "amr-wb")
                        .map(format -> Arguments.of(template.get()[0], template.get()[1], format)));
    }

    @ParameterizedTest(name = "{0} in {2}")
    @MethodSource("concatenations")
    void concatenatesFixedSoundsAroundTheAudio(final String template, final String templateParts, final String format) {
        final AudioFormat audioFormat = AudioFormat.fromCode(format).orElseThrow();
        final List<String> parts = List.of(templateParts.split(","));

        final Response response = RestAssured.given().queryParam("format", format)
                .post("/audios/{id}/concatenations/{template}", audioId, template);

        response.then().statusCode(200).header("Content-Type", audioFormat.getMimeType());
        final byte[] output = response.asByteArray();
        final Duration expected = parts.stream()
                .map(part -> "INPUT".equals(part) ? AudioFixtures.sample(SAMPLE, audioFormat) : AudioFixtures.fixedSound(part, audioFormat))
                .map(AudioFixtures::duration)
                .reduce(Duration.ZERO, Duration::plus);
        Assertions.assertThat(audioFormat.matchesSignature(Arrays.copyOf(output, AudioFormat.SIGNATURE_LENGTH))).isTrue();
        Assertions.assertThat(AudioFixtures.duration(output)).isCloseTo(expected, TOLERANCE);
    }

    @Test
    void renamesTheAudio() {
        RestAssured.given().contentType("application/json").body("{\"name\":\"Dust of Snow\"}")
                .patch("/audios/{id}", audioId)
                .then().statusCode(200).body("name", Matchers.equalTo("Dust of Snow"));

        RestAssured.given().get("/audios/{id}", audioId).then().body("name", Matchers.equalTo("Dust of Snow"));
    }

    @Test
    void deletesTheAudioAndItsContent() {
        RestAssured.given().delete("/audios/{id}", audioId).then().statusCode(204);

        RestAssured.given().get("/audios/{id}", audioId).then().statusCode(404);
        for (final AudioFormat format : AudioFixtures.PROPOSED) {
            final String key = StorageKeys.variant(AudioId.parse(audioId), format);
            final boolean exists = storage.exists(key).subscribe().withSubscriber(UniAssertSubscriber.create())
                    .awaitItem(Duration.ofSeconds(5)).getItem();
            Assertions.assertThat(exists).as(key).isFalse();
        }
    }

    @Test
    void refusesASecondSynchronization() {
        RestAssured.given().contentType("application/json").body("{\"externalId\":\"" + externalId + "\"}")
                .post("/audios/synchronizations")
                .then().statusCode(409).body("code", Matchers.equalTo("AUDIO_ALREADY_SYNCHRONIZED"));
    }

    @Test
    void answersProblemsWithoutInternalDetails() {
        final String json = RestAssured.given().get("/audios/{id}", UUID.randomUUID()).then()
                .statusCode(404).contentType("application/problem+json")
                .extract().asString();

        JsonAssertions.assertThatJson(json).isEqualTo("""
                {
                  "type": "urn:problem:audio-demo:audio-not-found",
                  "title": "Not Found",
                  "status": 404,
                  "detail": "Audio not found",
                  "code": "AUDIO_NOT_FOUND",
                  "correlationId": "${json-unit.any-string}"
                }""");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/audios/not-a-uuid",
            "/audios/%s/content?format=wav",
            "/audios/%s/content?format=../../etc"
    })
    void rejectsInvalidParameters(final String path) {
        RestAssured.given().urlEncodingEnabled(false).get(path.formatted(audioId)).then().statusCode(400)
                .body("code", Matchers.equalTo("INVALID_REQUEST"));
    }

    @Test
    void rejectsAnUnknownTemplate() {
        RestAssured.given().post("/audios/{id}/concatenations/{template}", audioId, "nope")
                .then().statusCode(400).contentType("application/problem+json")
                .body("code", Matchers.equalTo("INVALID_REQUEST"))
                .body("detail", Matchers.equalTo("Unknown template 'nope'"));
    }

    @Test
    void reportsAMissingFixedSound() {
        // configured in application-test.yml
        RestAssured.given().post("/audios/{id}/concatenations/{template}", audioId, "unknown-sound")
                .then().statusCode(404).body("code", Matchers.equalTo("AUDIO_NOT_FOUND"));
    }

    @Test
    @SuppressWarnings("UastIncorrectHttpHeaderInspection") // standard header, unknown to the IDE list
    void sendsSecurityHeaders() {
        RestAssured.given().get("/audios/{id}", audioId).then()
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .header("Cache-Control", "no-store");
    }

    private static byte[] fixture(final AudioFormat format) {
        return AudioFixtures.bytes(AudioFixtures.sample(SAMPLE, format));
    }

    private static int size(final AudioFormat format) {
        return fixture(format).length;
    }

    private static String md5(final AudioFormat format) {
        return Api.md5(fixture(format));
    }
}
