package com.example.audio.adapters.input.rest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.AudioFixtures;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.response.Response;

/** Same test resources as the other component tests, so they all share one application and its Dev Services. */
@QuarkusTest
@ConnectWireMock
class AudioProcessingResourceTest {

    private static final Duration TOLERANCE = Duration.ofMillis(300);

    static Stream<Arguments> transcodings() {
        return Arrays.stream(AudioFormat.values())
                .flatMap(source -> Stream.of(Arguments.of(source, "mp3", AudioFormat.MP3), Arguments.of(source, "amr", AudioFormat.AMR_WB)));
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("transcodings")
    void transcodesAnUploadedAudio(final AudioFormat source, final String target, final AudioFormat expected) {
        // the longest sample: the upload is larger than the JSON body limit
        final Path input = AudioFixtures.sample("greatthings_asm", source);

        final Response response = RestAssured.given().contentType(source.getMimeType()).queryParam("target", target)
                .body(AudioFixtures.bytes(input))
                .post("/transcodings");

        response.then().statusCode(200).header("Content-Type", expected.getMimeType());
        final byte[] output = response.asByteArray();
        Assertions.assertThat(expected.matchesSignature(Arrays.copyOf(output, AudioFormat.SIGNATURE_LENGTH))).isTrue();
        Assertions.assertThat(AudioFixtures.duration(output)).isCloseTo(AudioFixtures.duration(input), TOLERANCE);
    }

    /** The {@code greeting} template of application.yml: a, INPUT, b. */
    @ParameterizedTest
    @EnumSource(names = { "MP3", "AMR_NB", "AMR_WB" })
    void concatenatesFixedSoundsAroundAnUploadedAudio(final AudioFormat format) {
        final Path input = AudioFixtures.sample("dustofsnow_wt", format);

        final Response response = RestAssured.given().contentType(format.getMimeType()).body(AudioFixtures.bytes(input))
                .post("/concatenations/{template}", "greeting");

        response.then().statusCode(200).header("Content-Type", format.getMimeType());
        final byte[] output = response.asByteArray();
        final Duration expected = AudioFixtures.duration(AudioFixtures.fixedSound("a", format))
                .plus(AudioFixtures.duration(input))
                .plus(AudioFixtures.duration(AudioFixtures.fixedSound("b", format)));
        Assertions.assertThat(format.matchesSignature(Arrays.copyOf(output, AudioFormat.SIGNATURE_LENGTH))).isTrue();
        Assertions.assertThat(AudioFixtures.duration(output)).isCloseTo(expected, TOLERANCE);
    }

    @Test
    void refusesToConcatenateAFormatWithoutFixedSounds() {
        RestAssured.given().contentType(AudioFormat.WAV.getMimeType())
                .body(AudioFixtures.bytes(AudioFixtures.sample("stars_jg", AudioFormat.WAV)))
                .post("/concatenations/{template}", "greeting")
                .then().statusCode(415);
    }

    @Test
    void rejectsContentNotMatchingItsType() {
        RestAssured.given().contentType("audio/amr-wb").body(AudioFixtures.bytes(AudioFixtures.sample("stars_jg", AudioFormat.MP3)))
                .post("/transcodings")
                .then().statusCode(422).body("code", Matchers.equalTo("INVALID_AUDIO"));
    }

    @Test
    void rejectsUnsupportedMediaTypes() {
        RestAssured.given().contentType("video/mp4").body(new byte[16]).post("/transcodings").then().statusCode(415);
    }

    @Test
    void rejectsWavAsTarget() {
        RestAssured.given().contentType("audio/mpeg").queryParam("target", "wav")
                .body(AudioFixtures.bytes(AudioFixtures.sample("stars_jg", AudioFormat.MP3)))
                .post("/transcodings")
                .then().statusCode(400).body("code", Matchers.equalTo("INVALID_REQUEST"));
    }

    @Test
    void rejectsUploadsAboveTheLimit() {
        // the server refuses before the body is sent, as for any client announcing its upload
        RestAssured.given().contentType("audio/wav").header("Expect", "100-continue")
                .config(RestAssured.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.protocol.expect-continue", true)))
                .body(new byte[6 * 1024 * 1024]).post("/transcodings")
                .then().statusCode(413);
    }
}
