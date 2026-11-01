package com.example.audio.support;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

/** Calls of the service API shared by component tests. */
public final class Api {

    private Api() {
    }

    public static JsonPath synchronize(final String externalId) {
        return RestAssured.given().contentType("application/json").body("{\"externalId\":\"" + externalId + "\"}")
                .post("/audios/synchronizations")
                .then().statusCode(201)
                .extract().jsonPath();
    }

    public static byte[] content(final String audioId, final String format) {
        return RestAssured.given().queryParam("format", format)
                .get("/audios/{id}/content", audioId)
                .then().statusCode(200)
                .extract().asByteArray();
    }

    public static Duration duration(final JsonPath audio) {
        return Duration.parse(audio.getString("duration"));
    }

    public static String md5(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
