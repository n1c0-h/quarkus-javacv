package com.example.audio.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.Body;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.CoreDsl;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpDsl;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

/**
 * Every operation of the service, run concurrently with the same share of the requests, including failures caused by
 * the clients and by the external service. The external service is WireMock with the stubs of
 * {@code benchmark/wiremock/mappings}: they answer any generated id, whose last UUID group selects the case.
 * <p>
 * Open workload: requests are sent at the target rate whatever the response times, so the run passes only when the
 * service keeps up (no unexpected status, bounded 99th percentiles).
 */
public class AudioDemoSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final double REQUESTS_PER_MINUTE = Double.parseDouble(System.getProperty("requestsPerMinute", "20000"));
    private static final int RAMP_UP_SECONDS = Integer.getInteger("rampUpSeconds", 30);
    private static final int DURATION_SECONDS = Integer.getInteger("durationSeconds", 180);
    private static final int P99_MILLIS = Integer.getInteger("p99Millis", 2000);
    private static final Path SAMPLES_DIR = Path.of(System.getProperty("samples", "../src/test/resources/wiremock/__files/samples"));

    /** Short voice messages (33 to 36 s), offered by the external service in every format. */
    private static final List<String> SAMPLES = List.of("stars_jg", "dustofsnow_wt", "aplusbsquared_mlo");
    /** Format codes in the order of the flags of an external id. */
    private static final List<String> FORMATS = List.of("wav", "mp3", "amr-nb", "amr-wb");
    private static final List<String> PROPOSED = List.of("mp3", "amr-nb", "amr-wb");
    private static final Map<String, String> MIME_TYPES = Map.of(
            "wav", "audio/wav", "mp3", "audio/mpeg", "amr-nb", "audio/amr", "amr-wb", "audio/amr-wb");
    private static final List<String> TEMPLATES = List.of("greeting", "intro", "outro");
    /** Answers after the read timeout of the service (15 s): its own group, kept out of the latency assertions. */
    private static final String EXTERNAL_TIMEOUT = "external timeout";

    /** Uploaded bodies, by "format/sample". */
    private static final Map<String, byte[]> UPLOADS = loadUploads();

    private static final List<String> GROUPS = List.of("external metadata", "external content", "synchronize", "get",
            "download", "concatenate stored", "concatenate upload", "transcode", "rename", "delete", "client errors",
            "external errors");

    /** Audios synchronized before the run, for the operations on stored audios; read-only once the run starts. */
    private final AtomicReference<List<SeededAudio>> seeded = new AtomicReference<>(List.of());

    public AudioDemoSimulation() {
        final HttpProtocolBuilder protocol = HttpDsl.http.baseUrl(BASE_URL)
                .acceptHeader("*/*")
                // like a few client services with connection pools, not one connection per virtual user
                .shareConnections();

        final double perGroup = REQUESTS_PER_MINUTE / 60 / GROUPS.size();
        final List<PopulationBuilder> populations = new ArrayList<>(List.of(
                inject(externalMetadata(), perGroup),
                inject(externalContent(), perGroup),
                inject(synchronize(), perGroup),
                inject(get(), perGroup),
                inject(download(), perGroup),
                inject(concatenateStored(), perGroup),
                inject(concatenateUpload(), perGroup),
                inject(transcode(), perGroup),
                inject(rename(), perGroup),
                // three requests per user: synchronize, delete, get
                inject(delete(), perGroup / 3),
                inject(clientErrors(), perGroup),
                // one of the nine external failures is the timeout, run on its own
                inject(externalErrors(), perGroup * 8 / 9),
                inject(externalTimeout(), perGroup / 9)));

        final List<Assertion> assertions = new ArrayList<>();
        assertions.add(CoreDsl.global().failedRequests().count().is(0L));
        GROUPS.forEach(group -> assertions.add(CoreDsl.details(group).responseTime().percentile(99.0).lt(P99_MILLIS)));
        assertions.add(CoreDsl.details(EXTERNAL_TIMEOUT).responseTime().max().lt(20_000));

        setUp(populations.stream().map(population -> population.protocols(protocol)).toList()).assertions(assertions);
    }

    /** Ramps up to the rate, then keeps it. */
    private static PopulationBuilder inject(final ScenarioBuilder scenario, final double usersPerSecond) {
        final List<OpenInjectionStep> steps = List.of(
                CoreDsl.rampUsersPerSec(0.1).to(usersPerSecond).during(RAMP_UP_SECONDS),
                CoreDsl.constantUsersPerSec(usersPerSecond).during(DURATION_SECONDS));
        return scenario.injectOpen(steps);
    }

    @Override
    public void before() {
        seeded.set(seed());
    }

    // --- external service ----------------------------------------------------------------------------------------

    private ScenarioBuilder externalMetadata() {
        return CoreDsl.scenario("external metadata").feed(feeder(() -> Map.of("externalId", externalId(randomSample(), randomSubset()))))
                .group("external metadata").on(CoreDsl.exec(HttpDsl.http("external metadata").get("/external-audios/#{externalId}")
                        .check(HttpDsl.status().is(200), CoreDsl.jsonPath("$.formats[0].format").exists())));
    }

    private ScenarioBuilder externalContent() {
        return CoreDsl.scenario("external content").feed(feeder(() -> {
            final int subset = randomSubset();
            final List<String> offered = offered(subset);
            final String format = offered.get(ThreadLocalRandom.current().nextInt(offered.size()));
            return Map.of("externalId", externalId(randomSample(), subset), "format", format, "mime", MIME_TYPES.get(format));
        })).group("external content").on(CoreDsl.exec(HttpDsl.http("external content #{format}")
                .get("/external-audios/#{externalId}/content").queryParam("format", "#{format}")
                .check(HttpDsl.status().is(200), HttpDsl.header("Content-Type").isEL("#{mime}"), CoreDsl.bodyLength().gt(10_000))));
    }

    // --- stored audios -------------------------------------------------------------------------------------------

    /** All 15 combinations of formats offered by the external service, and the three samples. */
    private ScenarioBuilder synchronize() {
        return CoreDsl.scenario("synchronize").feed(feeder(() -> Map.of("externalId", externalId(randomSample(), randomSubset()))))
                .group("synchronize").on(CoreDsl.exec(synchronizeRequest("synchronize")));
    }

    private ScenarioBuilder get() {
        return CoreDsl.scenario("get").feed(feeder(() -> Map.of("id", randomSeeded().id())))
                .group("get").on(CoreDsl.exec(HttpDsl.http("get").get("/audios/#{id}")
                        .check(HttpDsl.status().is(200), CoreDsl.jsonPath("$.variants[0].md5").exists())));
    }

    private ScenarioBuilder download() {
        return CoreDsl.scenario("download").feed(feeder(() -> {
            final SeededAudio audio = randomSeeded();
            return Map.of("id", audio.id(), "format", random(audio.selectors()));
        })).group("download").on(CoreDsl.exec(HttpDsl.http("download #{format}").get("/audios/#{id}/content")
                .queryParam("format", "#{format}")
                .check(HttpDsl.status().is(200), CoreDsl.bodyLength().gt(10_000))));
    }

    private ScenarioBuilder concatenateStored() {
        return CoreDsl.scenario("concatenate stored").feed(feeder(() -> {
            final SeededAudio audio = randomSeeded();
            final String format = random(audio.formats());
            return Map.of("id", audio.id(), "format", format, "mime", MIME_TYPES.get(format), "template", random(TEMPLATES));
        })).group("concatenate stored").on(CoreDsl.exec(HttpDsl.http("concatenate stored #{template} #{format}")
                .post("/audios/#{id}/concatenations/#{template}").queryParam("format", "#{format}")
                .check(HttpDsl.status().is(200), HttpDsl.header("Content-Type").isEL("#{mime}"), CoreDsl.bodyLength().gt(10_000))));
    }

    private ScenarioBuilder concatenateUpload() {
        return CoreDsl.scenario("concatenate upload").feed(feeder(() -> {
            final String format = random(PROPOSED);
            return Map.of("upload", format + "/" + SAMPLES.get(randomSample()), "format", format, "mime", MIME_TYPES.get(format),
                    "template", random(TEMPLATES));
        })).group("concatenate upload").on(CoreDsl.exec(HttpDsl.http("concatenate upload #{template} #{format}")
                .post("/concatenations/#{template}").header("Content-Type", "#{mime}").body(uploadBody())
                .check(HttpDsl.status().is(200), HttpDsl.header("Content-Type").isEL("#{mime}"), CoreDsl.bodyLength().gt(10_000))));
    }

    /** Every source format to both targets. */
    private ScenarioBuilder transcode() {
        return CoreDsl.scenario("transcode").feed(feeder(() -> {
            final String source = random(FORMATS);
            final String target = random(List.of("mp3", "amr"));
            return Map.of("upload", source + "/" + SAMPLES.get(randomSample()), "source", source, "mime", MIME_TYPES.get(source),
                    "target", target, "expected", "mp3".equals(target) ? "audio/mpeg" : "audio/amr-wb");
        })).group("transcode").on(CoreDsl.exec(HttpDsl.http("transcode #{source} to #{target}").post("/transcodings")
                .queryParam("target", "#{target}").header("Content-Type", "#{mime}").body(uploadBody())
                .check(HttpDsl.status().is(200), HttpDsl.header("Content-Type").isEL("#{expected}"), CoreDsl.bodyLength().gt(10_000))));
    }

    private ScenarioBuilder rename() {
        return CoreDsl.scenario("rename")
                .feed(feeder(() -> Map.of("id", randomSeeded().id(), "name", "Renamed " + ThreadLocalRandom.current().nextInt(1_000_000))))
                .group("rename").on(CoreDsl.exec(HttpDsl.http("rename").patch("/audios/#{id}")
                        .header("Content-Type", "application/json").body(CoreDsl.StringBody("{\"name\":\"#{name}\"}"))
                        .check(HttpDsl.status().is(200), CoreDsl.jsonPath("$.name").isEL("#{name}"))));
    }

    /** Deletes an audio synchronized for that purpose, then checks it is gone. */
    private ScenarioBuilder delete() {
        return CoreDsl.scenario("delete").feed(feeder(() -> Map.of("externalId", externalId(randomSample(), randomSubset()))))
                .group("delete").on(CoreDsl.exec(
                        synchronizeRequest("delete: synchronize"),
                        HttpDsl.http("delete").delete("#{location}").check(HttpDsl.status().is(204)),
                        HttpDsl.http("delete: get deleted").get("#{location}").check(HttpDsl.status().is(404))));
    }

    private static HttpRequestActionBuilder synchronizeRequest(final String name) {
        return HttpDsl.http(name).post("/audios/synchronizations").header("Content-Type", "application/json")
                .body(CoreDsl.StringBody("{\"externalId\":\"#{externalId}\"}"))
                .check(HttpDsl.status().is(201), HttpDsl.header("Location").saveAs("location"),
                        CoreDsl.jsonPath("$.variants[1]").exists());
    }

    // --- failures ------------------------------------------------------------------------------------------------

    /** Invalid requests: every one is answered with its expected problem status, quickly. */
    private ScenarioBuilder clientErrors() {
        final List<ChainBuilder> cases = List.of(
                clientError("invalid id", "GET", audio -> "/audios/not-a-uuid", null, null, 400),
                clientError("unknown audio", "GET", audio -> "/audios/" + UUID.randomUUID(), null, null, 404),
                clientError("wav is not served", "GET", audio -> "/audios/" + audio.id() + "/content?format=wav", null, null, 400),
                clientError("path traversal in format", "GET", audio -> "/audios/" + audio.id() + "/content?format=..%2F..%2Fetc", null,
                        null, 400),
                clientError("missing variant", "GET", audio -> missingVariantPath(), null, null,
                        404),
                clientError("invalid external id", "POST", audio -> "/audios/synchronizations", "application/json",
                        audio -> "{\"externalId\":\"nope\"}", 400),
                clientError("malformed json", "POST", audio -> "/audios/synchronizations", "application/json", audio -> "{", 400),
                clientError("unknown json property", "POST", audio -> "/audios/synchronizations", "application/json",
                        audio -> "{\"externalId\":\"" + audio.externalId() + "\",\"admin\":true}", 400),
                clientError("already synchronized", "POST", audio -> "/audios/synchronizations", "application/json",
                        audio -> "{\"externalId\":\"" + audio.externalId() + "\"}", 409),
                clientError("unknown template", "POST", audio -> "/audios/" + audio.id() + "/concatenations/nope", null, null, 400),
                clientError("unsupported media type", "POST", audio -> "/transcodings", "video/mp4", audio -> "not a video", 415),
                clientError("content not matching its type", "POST", audio -> "/transcodings", "audio/amr-wb",
                        audio -> "ID3 this is no amr content", 422),
                clientError("wav concatenation", "POST", audio -> "/concatenations/greeting", "audio/wav", audio -> "RIFF....WAVE", 415),
                clientError("blank name", "PATCH", audio -> "/audios/" + audio.id(), "application/json", audio -> "{\"name\":\" \"}", 400),
                clientError("delete unknown audio", "DELETE", audio -> "/audios/" + UUID.randomUUID(), null, null, 404));
        return CoreDsl.scenario("client errors")
                .group("client errors").on(CoreDsl.uniformRandomSwitch().on(cases));
    }

    private ChainBuilder clientError(final String name, final String method, final Function<SeededAudio, String> path,
            final String contentType, final Function<SeededAudio, String> body, final int status) {
        final HttpRequestActionBuilder request = HttpDsl.http("client error: " + name)
                .httpRequest(method, "#{path}").check(HttpDsl.status().is(status), HttpDsl.header("Content-Type").is("application/problem+json"));
        final ChainBuilder feed = CoreDsl.feed(feeder(() -> {
            final SeededAudio audio = randomSeeded();
            return Map.of("path", path.apply(audio), "body", body == null ? "" : body.apply(audio));
        }));
        return feed.exec(contentType == null ? request
                : request.header("Content-Type", contentType).body(CoreDsl.StringBody("#{body}")));
    }

    /** Failures of the external service: 404, errors, connection reset, invalid or inconsistent answers, broken bodies. */
    private ScenarioBuilder externalErrors() {
        final List<ChainBuilder> cases = List.of(
                externalError(1, "not found", "metadata", 404),
                externalError(1, "not found", "synchronize", 404),
                externalError(2, "server error", "metadata", 502),
                externalError(2, "server error", "synchronize", 502),
                externalError(4, "connection reset", "metadata", 502),
                externalError(4, "connection reset", "synchronize", 502),
                externalError(5, "malformed json", "metadata", 502),
                externalError(5, "malformed json", "synchronize", 502),
                externalError(6, "answer for another audio", "metadata", 502),
                externalError(6, "answer for another audio", "synchronize", 502),
                externalError(7, "content server error", "content", 502),
                externalError(7, "content server error", "synchronize", 502),
                externalError(8, "content connection closed", "content", 502),
                externalError(8, "content connection closed", "synchronize", 502),
                externalError(9, "content not matching its type", "synchronize", 422));
        return CoreDsl.scenario("external errors")
                .group("external errors").on(CoreDsl.uniformRandomSwitch().on(cases));
    }

    private ScenarioBuilder externalTimeout() {
        return CoreDsl.scenario(EXTERNAL_TIMEOUT).group(EXTERNAL_TIMEOUT)
                .on(CoreDsl.uniformRandomSwitch().on(
                        externalError(3, "timeout", "metadata", 502),
                        externalError(3, "timeout", "synchronize", 502)));
    }

    private ChainBuilder externalError(final int failure, final String name, final String operation, final int status) {
        final String requestName = "external error: " + name + " (" + operation + ")";
        final HttpRequestActionBuilder request = switch (operation) {
            case "metadata" -> HttpDsl.http(requestName).get("/external-audios/#{externalId}");
            case "content" -> HttpDsl.http(requestName).get("/external-audios/#{externalId}/content").queryParam("format", "mp3");
            default -> HttpDsl.http(requestName).post("/audios/synchronizations").header("Content-Type", "application/json")
                    .body(CoreDsl.StringBody("{\"externalId\":\"#{externalId}\"}"));
        };
        return CoreDsl.feed(feeder(() -> Map.of("externalId", failureId(failure))))
                .exec(request.check(HttpDsl.status().is(status), HttpDsl.header("Content-Type").is("application/problem+json")));
    }

    // --- data ----------------------------------------------------------------------------------------------------

    /**
     * An id answered by the stubs: the last group is "bee", the sample index, "0000", then one flag per offered format
     * (wav, mp3, amr-nb, amr-wb).
     */
    private static String externalId(final int sample, final int subset) {
        final StringBuilder flags = new StringBuilder();
        for (int i = 0; i < FORMATS.size(); i++) {
            flags.append((subset >> i) & 1);
        }
        return randomPrefix() + "bee" + sample + "0000" + flags;
    }

    /** An id answered with one of the failures of {@code failures.json}. */
    private static String failureId(final int failure) {
        return randomPrefix() + "bee0ffff000" + failure;
    }

    /** The first four groups of a random UUID, which keeps every generated id unique. */
    private static String randomPrefix() {
        return UUID.randomUUID().toString().substring(0, 24);
    }

    private static List<String> offered(final int subset) {
        final List<String> offered = new ArrayList<>();
        for (int i = 0; i < FORMATS.size(); i++) {
            if (((subset >> i) & 1) == 1) {
                offered.add(FORMATS.get(i));
            }
        }
        return offered;
    }

    /** Synchronizes every combination of offered formats for every sample, one after the other. */
    private static List<SeededAudio> seed() {
        final List<SeededAudio> audios = new ArrayList<>();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int sample = 0; sample < SAMPLES.size(); sample++) {
                for (int subset = 1; subset < 16; subset++) {
                    final String externalId = externalId(sample, subset);
                    final HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/audios/synchronizations"))
                            .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"externalId\":\"" + externalId + "\"}")).build();
                    final HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() != 201) {
                        throw new IllegalStateException("Seeding " + externalId + " answered " + response.statusCode());
                    }
                    final String location = response.headers().firstValue("Location").orElseThrow();
                    audios.add(SeededAudio.of(location.substring(location.lastIndexOf('/') + 1), externalId, offered(subset)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Seeding failed: is the service running on " + BASE_URL + "?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Seeding interrupted", e);
        }
        return List.copyOf(audios);
    }

    private static Map<String, byte[]> loadUploads() {
        final Map<String, String> extensions = Map.of("wav", "wav", "mp3", "mp3", "amr-nb", "amr", "amr-wb", "amr");
        final Map<String, byte[]> uploads = new HashMap<>();
        for (final String format : FORMATS) {
            for (final String sample : SAMPLES) {
                try {
                    uploads.put(format + "/" + sample, Files.readAllBytes(SAMPLES_DIR.resolve(format).resolve(sample + "." + extensions.get(format))));
                } catch (IOException e) {
                    throw new UncheckedIOException("Samples not found in " + SAMPLES_DIR.toAbsolutePath(), e);
                }
            }
        }
        return Map.copyOf(uploads);
    }

    private static Body uploadBody() {
        return CoreDsl.ByteArrayBody(session -> UPLOADS.get(session.getString("upload")));
    }

    private static Iterator<Map<String, Object>> feeder(final Supplier<Map<String, Object>> generator) {
        return Stream.generate(generator).iterator();
    }

    /** The content of a stored audio in the one proposed format it lacks. */
    private String missingVariantPath() {
        final SeededAudio audio = random(seeded.get().stream().filter(candidate -> candidate.missing() != null).toList());
        return "/audios/" + audio.id() + "/content?format=" + audio.missing();
    }

    private SeededAudio randomSeeded() {
        return random(seeded.get());
    }

    private static int randomSample() {
        return ThreadLocalRandom.current().nextInt(SAMPLES.size());
    }

    private static int randomSubset() {
        return ThreadLocalRandom.current().nextInt(1, 16);
    }

    private static <T> T random(final List<T> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    /**
     * A synchronized audio. Its variants follow the rules of the service: every offered proposed format, mp3 when
     * missing, amr-wb when no amr is offered.
     *
     * @param formats   the stored formats
     * @param selectors the accepted values of {@code ?format=} (with the {@code amr} alias)
     * @param missing   the proposed format that is not stored, null when all are
     */
    private record SeededAudio(String id, String externalId, List<String> formats, List<String> selectors, String missing) {

        static SeededAudio of(final String id, final String externalId, final List<String> offered) {
            final boolean amrNb = offered.contains("amr-nb");
            final boolean amrWb = offered.contains("amr-wb") || !amrNb;
            final List<String> formats = Stream.of("mp3", amrNb ? "amr-nb" : null, amrWb ? "amr-wb" : null)
                    .filter(Objects::nonNull).toList();
            final List<String> selectors = Stream.concat(formats.stream(), Stream.of("amr")).toList();
            // amr-wb is stored whenever amr-nb is not: an audio misses at most one of them
            final String missing;
            if (amrNb && amrWb) {
                missing = null;
            } else {
                missing = amrNb ? "amr-wb" : "amr-nb";
            }
            return new SeededAudio(id, externalId, formats, selectors, missing);
        }
    }
}
