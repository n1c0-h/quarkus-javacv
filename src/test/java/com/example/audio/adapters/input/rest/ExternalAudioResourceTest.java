package com.example.audio.adapters.input.rest;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.AudioFixtures;
import com.example.audio.support.ExternalCatalogStub;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import net.javacrumbs.jsonunit.assertj.JsonAssertions;

@QuarkusTest
@ConnectWireMock
class ExternalAudioResourceTest {

    private static final String MISSING = UUID.randomUUID().toString();
    private static final String BROKEN = UUID.randomUUID().toString();
    private static final String SLOW = UUID.randomUUID().toString();
    private static final String GONE = UUID.randomUUID().toString();

    WireMock wiremock;

    @Test
    void proxiesTheMetadataAndDropsUnknownFields() {
        final String id = UUID.randomUUID().toString();
        new ExternalCatalogStub(wiremock).audio(id, "stars_jg", List.of(AudioFormat.WAV, AudioFormat.AMR_NB));

        final String json = RestAssured.given().get("/external-audios/{id}", id).then().statusCode(200).extract().asString();

        JsonAssertions.assertThatJson(json).isEqualTo("""
                {"id": "%s", "name": "stars_jg", "formats": [
                  {"format": "wav", "size": %d},
                  {"format": "amr-nb", "size": %d}
                ]}""".formatted(id, size(AudioFormat.WAV), size(AudioFormat.AMR_NB)));
    }

    @Test
    void streamsTheExternalContent() {
        final String id = UUID.randomUUID().toString();
        new ExternalCatalogStub(wiremock).audio(id, "herlips_tp", List.of(AudioFormat.WAV));

        final Response response = RestAssured.given().queryParam("format", "wav").get("/external-audios/{id}/content", id);

        response.then().statusCode(200).header("Content-Type", "audio/wav");
        Assertions.assertThat(response.asByteArray()).isEqualTo(AudioFixtures.bytes(AudioFixtures.sample("herlips_tp", AudioFormat.WAV)));
    }

    @Test
    void mapsAnUnknownExternalAudioToNotFound() {
        new ExternalCatalogStub(wiremock).metadataStatus(MISSING, 404);

        RestAssured.given().get("/external-audios/" + MISSING).then().statusCode(404).body("code", Matchers.equalTo("AUDIO_NOT_FOUND"));
    }

    @Test
    void hidesExternalFailures() {
        new ExternalCatalogStub(wiremock).metadataStatus(BROKEN, 500);

        RestAssured.given().get("/external-audios/" + BROKEN).then().statusCode(502)
                .body("code", Matchers.equalTo("EXTERNAL_SERVICE"))
                .body(Matchers.not(Matchers.containsString("secret")));
    }

    @Test
    void timesOutOnASlowExternalService() {
        new ExternalCatalogStub(wiremock).slowMetadata(SLOW, 3_000);

        RestAssured.given().get("/external-audios/" + SLOW).then().statusCode(502).body("code", Matchers.equalTo("EXTERNAL_SERVICE"));
    }

    @Test
    void synchronizesNothingWhenTheExternalAudioIsUnknown() {
        new ExternalCatalogStub(wiremock).metadataStatus(GONE, 404);

        RestAssured.given().contentType("application/json").body("{\"externalId\":\"" + GONE + "\"}")
                .post("/audios/synchronizations")
                .then().statusCode(404).body("code", Matchers.equalTo("AUDIO_NOT_FOUND"));
    }

    /** Size of the "stars_jg" sample served by the stub. */
    private static int size(final AudioFormat format) {
        return AudioFixtures.bytes(AudioFixtures.sample("stars_jg", format)).length;
    }
}
