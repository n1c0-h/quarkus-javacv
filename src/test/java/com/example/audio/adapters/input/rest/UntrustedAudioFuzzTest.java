package com.example.audio.adapters.input.rest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.audio.domain.models.AudioFormat;
import com.example.audio.support.AudioFixtures;
import com.example.audio.support.AudioMutator;
import com.example.audio.support.ExternalCatalogStub;
import com.example.audio.support.FuzzCaseFile;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

/**
 * Mutation fuzzing through the API: audio uploaded by users and audio served by the external service. Every
 * answer is a success with valid audio or a {@code 422}; nothing is stored that does not have the signature of its
 * format. A JVM crash leaves the case in {@code target/fuzz}. {@code mvn verify -Pfuzz} (or
 * {@code -Dfuzz.http.cases=N -Dfuzz.seed=S}) runs a longer campaign.
 */
@QuarkusTest
@ConnectWireMock
class UntrustedAudioFuzzTest {

    private static final long SEED = Long.getLong("fuzz.seed", 20_261_101L);
    private static final int CASES_PER_FORMAT = Integer.getInteger("fuzz.http.cases", 12);
    /** Only a hang reaches it. */
    private static final RestAssuredConfig TIMEOUTS = RestAssured.config().httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.socket.timeout", 30_000).setParam("http.connection.timeout", 5_000));

    /**
     * Parallel clients, as in production. A synchronization takes up to two FFmpeg jobs (one per offered format): four
     * clients stay within the eight concurrent jobs the test profile allows, above which the service answers 503.
     */
    private static final int CLIENTS = 4;

    WireMock wiremock;

    private final FuzzCaseFile currentCases = new FuzzCaseFile("UntrustedAudioFuzzTest");
    private final ExecutorService clients = Executors.newFixedThreadPool(CLIENTS);

    @AfterEach
    void stop() {
        clients.shutdownNow();
        currentCases.close();
    }

    @Test
    void processesOrRejectsMutatedUploads() {
        final List<Callable<List<String>>> cases = new ArrayList<>();
        for (final AudioFormat format : AudioFormat.values()) {
            final byte[] valid = AudioFixtures.bytes(AudioFixtures.sample("stars_jg", format));
            for (int index = 0; index < CASES_PER_FORMAT; index++) {
                final AudioMutator.Mutation mutation = AudioMutator.mutate(valid, format, SEED, index);
                final String name = format.getCode() + " #" + index + " (" + mutation.description() + ")";
                final String target = index % 2 == 0 ? "mp3" : "amr";
                cases.add(fuzzCase(name + " upload", failures -> {
                    checkStream(failures, name + " transcode", RestAssured.given().config(TIMEOUTS).contentType(format.getMimeType())
                            .queryParam("target", target).body(mutation.data()).post("/transcodings"),
                            "mp3".equals(target) ? AudioFormat.MP3 : AudioFormat.AMR_WB);
                    if (format.isProposed()) {
                        checkStream(failures, name + " concatenate", RestAssured.given().config(TIMEOUTS).contentType(format.getMimeType())
                                .body(mutation.data()).post("/concatenations/{template}", "greeting"), format);
                    }
                }));
            }
        }
        Assertions.assertThat(runAll(cases)).isEmpty();
    }

    @Test
    void storesOrRejectsMutatedExternalAudio() {
        final List<Callable<List<String>>> cases = new ArrayList<>();
        final ExternalCatalogStub catalog = new ExternalCatalogStub(wiremock);
        for (final AudioFormat format : AudioFormat.values()) {
            final byte[] valid = AudioFixtures.bytes(AudioFixtures.sample("stars_jg", format));
            for (int index = 0; index < CASES_PER_FORMAT; index++) {
                final AudioMutator.Mutation mutation = AudioMutator.mutate(valid, format, SEED, index);
                final Map<AudioFormat, byte[]> offered = new EnumMap<>(AudioFormat.class);
                offered.put(format, mutation.data());
                // half of the AMR cases next to a valid MP3: the AMR variant is then stored without being transcoded
                if (format.isAmr() && index % 2 == 1) {
                    offered.put(AudioFormat.MP3, AudioFixtures.bytes(AudioFixtures.sample("stars_jg", AudioFormat.MP3)));
                }
                final String name = format.getCode() + " #" + index + " (" + mutation.description() + ") offered " + offered.keySet();
                cases.add(fuzzCase(name + " synchronization", failures -> {
                    final String externalId = UUID.randomUUID().toString();
                    catalog.audio(externalId, "fuzz", offered);
                    checkSynchronization(failures, name, externalId);
                }));
            }
        }
        Assertions.assertThat(runAll(cases)).isEmpty();
    }

    /** A case collects its own failures, and is recorded as running while it runs. */
    private Callable<List<String>> fuzzCase(final String name, final Consumer<List<String>> checks) {
        return () -> {
            final List<String> failures = new ArrayList<>();
            currentCases.run(name + " (seed " + SEED + ")", () -> checks.accept(failures));
            return failures;
        };
    }

    private List<String> runAll(final List<Callable<List<String>>> cases) {
        try {
            final List<String> failures = new ArrayList<>();
            for (final Future<List<String>> result : clients.invokeAll(cases)) {
                failures.addAll(result.get());
            }
            return failures;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fuzzing interrupted", e);
        } catch (ExecutionException e) {
            throw new AssertionError("A fuzz case failed unexpectedly", e.getCause());
        }
    }

    private static void checkStream(final List<String> failures, final String name, final Response response, final AudioFormat expected) {
        final int status = response.statusCode();
        if (status == 422) {
            return;
        }
        if (status != 200) {
            failures.add(name + ": status " + status + " " + response.asString() + " (seed " + SEED + ")");
            return;
        }
        final byte[] body = response.asByteArray();
        if (!expected.matchesSignature(Arrays.copyOf(body, AudioFormat.SIGNATURE_LENGTH))) {
            failures.add(name + ": 200 without valid " + expected.getCode() + " content (seed " + SEED + ")");
        }
    }

    private static void checkSynchronization(final List<String> failures, final String name, final String externalId) {
        final Response response = RestAssured.given().config(TIMEOUTS).contentType("application/json")
                .body("{\"externalId\":\"" + externalId + "\"}").post("/audios/synchronizations");
        final int status = response.statusCode();
        if (status == 422) {
            return;
        }
        if (status != 201) {
            failures.add(name + ": status " + status + " " + response.asString() + " (seed " + SEED + ")");
            return;
        }
        final JsonPath audio = response.jsonPath();
        final List<Map<String, Object>> variants = audio.getList("variants");
        final Set<String> stored = Set.copyOf(variants.stream().map(v -> (String) v.get("format")).toList());
        for (final String code : stored) {
            final AudioFormat format = AudioFormat.fromCode(code).orElseThrow();
            final byte[] content = RestAssured.given().config(TIMEOUTS).queryParam("format", code)
                    .get("/audios/{id}/content", audio.getString("id")).then().statusCode(200).extract().asByteArray();
            if (!format.matchesSignature(Arrays.copyOf(content, AudioFormat.SIGNATURE_LENGTH))) {
                failures.add(name + ": stored " + code + " variant is not " + code + " audio (seed " + SEED + ")");
            }
        }
    }
}
