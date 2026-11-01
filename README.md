# audio-demo

A Quarkus 3.27 service demonstrating two things:

1. **Quarkus ↔ Bytedeco JavaCV / FFmpeg**: transcoding (WAV, MP3, AMR-NB, AMR-WB in; MP3 and AMR out),
   concatenation of an audio with fixed sounds following a template (`a, INPUT, b`, `c, INPUT`, `INPUT, d`), and
   exact duration measurement, all on streams.
2. **Streaming with backpressure** end to end: HTTP server (Quarkus REST), HTTP client (REST client), S3 upload and
   download (AWS SDK). No audio is ever fully loaded in memory.

## Architecture

DDD with an onion / clean architecture, in one Maven module (`com.example.audio`):

| Package                                     | Content                                                                                           |
|---------------------------------------------|---------------------------------------------------------------------------------------------------|
| `adapters.input.rest`                       | `AudioResource`, `ExternalAudioResource`, `TranscodingResource`                                   |
| `adapters.input.rest.dto`                   | request bodies without a domain equivalent, problem details                                       |
| `adapters.input.rest.exceptions`            | problem+json exception mappers                                                                    |
| `adapters.output.http`                      | REST client of the external audio service                                                         |
| `adapters.output.postgres`                  | repository on the reactive PostgreSQL client                                                      |
| `adapters.output.s3` (`.config`)            | storage on the S3 asynchronous client (client producer, settings)                                 |
| `adapters.output.ffmpeg` (`.config`)        | audio processing with JavaCV / FFmpeg (settings)                                                  |
| `adapters.common`                           | Jackson mixins (`@JacksonMixin`), shared by the HTTP server and client                            |
| `application.usecases`                      | `ExternalAudioService`, `AudioLibraryService`, `AudioContentService`                              |
| `application.config`                        | CDI producers                                                                                     |
| `domain.models`, `domain.services`          | `Audio`, `AudioVariant`, `AudioFormat`, `ConcatenationTemplate`… and `VariantPolicy` (sync rules) |
| `domain.exceptions`                         | business exceptions                                                                               |
| `domain.ports.input`, `domain.ports.output` | use case interfaces, adapter interfaces                                                           |

The domain and the use cases only depend on the JDK, Lombok and Mutiny (`Uni`/`Multi` are the reactive vocabulary
of the ports, a deliberate simplification).

## Business rules

**Synchronization** copies an external audio into the service. The external service may offer any subset of
`wav`, `mp3`, `amr-nb`, `amr-wb`; the service only proposes **mp3 and amr**:

- every offered mp3 / amr-nb / amr-wb is stored as-is (no transcoding);
- a missing mp3 is transcoded from wav, else amr-wb, else amr-nb;
- a missing amr (neither nb nor wb) is transcoded to **amr-wb** from wav, else mp3;
- wav is never stored; the duration is measured on the most accurate fetched format.

Each needed format is downloaded **once**, on its own worker thread, and feeds the S3 upload of the original, its MD5,
the duration measurement and the encoders of the missing formats at the same time. All 15 combinations are tested
(`SyncCases`).

**Concatenation** plays a stored or uploaded audio inside a named template configured in `application.yml`
(`audio.concatenation.templates`, e.g. `greeting: [a, INPUT, b]`; an invalid template prevents the start). The result
has the format and the codec parameters (sample rate, channels) of the input, whatever they are; fixed sounds are
stored in every proposed format. Nothing is re-encoded when the parameters match: the packets are copied. A fixed
sound whose parameters differ from the input (e.g. an MP3 input at 22.05 kHz, fixed sounds at 48 kHz) is re-encoded
alone, in memory, to the parameters of the input; AMR has fixed parameters, so AMR is always copied.
**Transcoding** (`/transcodings`) converts an uploaded audio to mp3 or amr (= amr-wb).

## API

| Method | Path                                                                                  |                                         |
|--------|---------------------------------------------------------------------------------------|-----------------------------------------|
| GET    | `/external-audios/{externalId}`                                                       | metadata from the external service      |
| GET    | `/external-audios/{externalId}/content?format=wav\|mp3\|amr-nb\|amr-wb`               | streamed from the external service      |
| POST   | `/audios/synchronizations` `{"externalId": "…"}`                                      | synchronize → `201` + `Location`        |
| GET    | `/audios/{id}`                                                                        | metadata from the database              |
| GET    | `/audios/{id}/content?format=mp3\|amr\|amr-nb\|amr-wb`                                | streamed from S3                        |
| POST   | `/audios/{id}/concatenations/{template}?format=…`                                     | streamed result                         |
| POST   | `/concatenations/{template}` (body: mp3 or amr audio, `Content-Type` = its MIME type) | streamed result                         |
| POST   | `/transcodings?target=mp3\|amr` (body: audio, `Content-Type` = its MIME type)         | streamed result                         |
| PATCH  | `/audios/{id}` `{"name": "…"}`                                                        | rename                                  |
| DELETE | `/audios/{id}`                                                                        | delete the row and every stored variant |

Errors are RFC 9457 `application/problem+json` with a stable `code` and a `correlationId` found in the logs.

```shell
# "Dust of Snow", one of the external audios mocked in src/test/resources/wiremock
curl -s localhost:8080/external-audios/c58380ad-1b90-5186-91be-2bae6e75e7f8
curl -s -XPOST localhost:8080/audios/synchronizations -H 'Content-Type: application/json' -d '{"externalId":"c58380ad-1b90-5186-91be-2bae6e75e7f8"}'
curl -s localhost:8080/audios/$ID/content?format=amr -o poem.amr
curl -s -XPOST "localhost:8080/audios/$ID/concatenations/greeting?format=mp3" -o message.mp3
curl -s -XPOST "localhost:8080/concatenations/intro" -H 'Content-Type: audio/amr-wb' --data-binary @poem.amr -o message.amr
curl -s -XPOST "localhost:8080/transcodings?target=amr" -H 'Content-Type: audio/mpeg' --data-binary @poem.mp3 -o poem.amr
```

## Streaming

FFmpeg is a blocking library, so audio content flows through plain `InputStream` / `OutputStream`, one small
buffer at a time; a write blocks until its destination accepted the previous bytes, so a slow consumer slows the
producer down and nothing is ever fully loaded in memory. Metadata and the database stay reactive (Mutiny).

| Flow            | Source → destination                                                                                                                                                                                             |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| synchronization | REST client body → `TeeInputStream` → FFmpeg (decodes once, encodes the missing formats into their S3 uploads; only demuxes when nothing is encoded) and, on the way, the S3 upload of the original with its MD5 |
| download        | S3 `ResponseInputStream` → HTTP response (`StreamingOutput`)                                                                                                                                                     |
| concatenation   | S3 streams (or upload) of the parts, read one after the other → FFmpeg packet copy → HTTP response                                                                                                               |
| transcoding     | HTTP request body → FFmpeg → HTTP response                                                                                                                                                                       |

S3 uploads use the SDK blocking output stream body on the asynchronous client with multipart uploads enabled: it
uploads a body of unknown length in parts by itself, and each upload consumes its own body independently of the
others. FFmpeg jobs are limited by a SmallRye Fault Tolerance `@Bulkhead`: when all are busy the service answers
`503` with `Retry-After`.

The synchronization endpoint is non-blocking: the existence check, the external metadata and the database insert
hold no thread, so a slow external service cannot exhaust the worker pool; only the copies of the external formats
run on worker threads. JavaCV opens and closes codecs under a JVM-wide lock, a leftover of FFmpeg < 4; FFmpeg 7
locks the few codecs that need it itself, so the service uses JavaCV's unlocked `startUnsafe`/`releaseUnsafe`.

JavaCV is pinned to 1.5.11: the natives of later releases need glibc 2.35, newer than EL 9's, and its bindings
match the FFmpeg 7.1 of EPEL 9 used by the image.

## Running

Requirements: a JDK ≥ 21 (the code targets Java 21), Maven ≥ 3.9, Podman. With rootless Podman:

```shell
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true
```

Dev Services start PostgreSQL, LocalStack (S3, seeded with the fixed sounds by
`src/test/resources/localstack/seed-fixed-sounds.sh`) and WireMock (the external service, see
`src/test/resources/wiremock`).

```shell
mvn verify                     # unit, component, leak and fuzz tests
mvn quarkus:dev                # http://localhost:8080/q/swagger-ui
```

Configuration: `application.yml` holds the defaults; `application-dev.yml` and `application-test.yml` the Dev
Services (the test file adds shorter limits). The PostgreSQL Dev Service creates the schema from
`src/main/resources/db/schema.sql`; in production, apply that script to the database once before the first start. In production, set
`EXTERNAL_AUDIO_URL`, `DB_URL` (e.g. `postgresql://host:5432/audio`), `DB_USER`, `DB_PASSWORD`, optionally
`AUDIO_BUCKET` (default `audio`), and the S3 client settings (`QUARKUS_S3_*`, see `compose.yaml`).

Testcontainers would mount the container engine socket into LocalStack, which SELinux rightly denies; the build
points `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` to an empty directory instead. Do the same in dev mode:
`export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=$PWD/target/test-classes/no-container-socket`.

## Production image

`src/main/docker/Containerfile` builds a ~630 MB image: AlmaLinux 9 micro (no package manager, no shell), a `jlink`
Java 21 runtime, FFmpeg 7.1 from EPEL (`ffmpeg-free`, with its AppStream/CRB dependencies), the Quarkus fast-jar;
base images pinned by digest. JavaCPP loads the system FFmpeg first (`-Dorg.bytedeco.javacpp.pathsFirst=true`):
its AMR and MP3 encoders are about twice as fast as the builds bundled in the JavaCV jars. Most of the size comes from
the dependencies of the EPEL FFmpeg libraries (VA-API → Mesa → LLVM, Samba for `smb://`…), unused for audio.

```shell
podman build -f src/main/docker/Containerfile -t localhost/audio-demo:1.0.0-SNAPSHOT .
podman compose up        # the image with PostgreSQL, LocalStack and WireMock (compose.yaml)
```

It runs as a non-root user with a read-only root filesystem; `/tmp` is the only writable path (JavaCPP extracts the
FFmpeg native libraries there, so it must allow execution). Health and metrics are on the management
port 9000 (`/q/health/live`, `/q/health/ready`, `/q/metrics`), not exposed with the API.

`FFMPEG_MAX_JOBS` (default 8) sets the concurrent FFmpeg jobs per operation (`@Bulkhead`, overridden through
`quarkus.fault-tolerance."<class>/<method>".bulkhead.value`); set it to about the number of hardware threads.

## Benchmark

`benchmark/` is a separate Gatling project (Java DSL) that loads the production image. Every operation gets the same
share of the requests, concurrently (open workload: requests are sent at the target rate whatever the response
times):

| Group                                  | Covers                                                                                            |
|----------------------------------------|---------------------------------------------------------------------------------------------------|
| external metadata, external content    | the proxy to the external service, every offered format                                           |
| synchronize                            | the 15 combinations of offered formats × 3 samples, fresh external ids                            |
| get, download, rename                  | stored audios (synchronized before the run), every format selector                                |
| concatenate stored, concatenate upload | the 3 configured templates × mp3, amr-nb, amr-wb                                                  |
| transcode                              | wav, mp3, amr-nb, amr-wb uploads to mp3 and amr                                                   |
| delete                                 | synchronize, delete, then get → `404`                                                             |
| client errors                          | invalid ids/formats/JSON, path traversal, missing variant, `409`, `415`, `422`, unknown template… |
| external errors, external timeout      | external 404, 500, connection reset, malformed or inconsistent JSON, broken bodies, timeout       |

Every request checks its expected status (and content type, size or JSON). The run fails when any request gets an
unexpected answer or when the 99th percentile of a group exceeds `p99Millis`. The external service is WireMock with
the stubs of `benchmark/wiremock/mappings`: they answer any generated id, whose last UUID group selects the sample
and the offered formats, or a failure.

```shell
podman build -f src/main/docker/Containerfile -t localhost/audio-demo:1.0.0-SNAPSHOT .
podman compose -f compose.yaml -f benchmark/compose.yaml up -d     # FFMPEG_MAX_JOBS=32, WireMock tuned for load
mvn -f benchmark/pom.xml gatling:test                              # report in benchmark/target/gatling/
# options: -DrequestsPerMinute=20000 -DrampUpSeconds=30 -DdurationSeconds=180 -Dp99Millis=2000 -DbaseUrl=…
```

On a Ryzen 9 9950X3D (16 cores, 32 threads) running the whole stack and Gatling: 20,000 requests/minute (45,014
requests over 30 s of ramp-up and 2 min), no failure, the service using 6.4 cores on average. The 99th percentiles
are 2 ms for `get`, 45 ms for `download`, 39 to 60 ms for the concatenations, 221 ms for `transcode`, 338 ms for
`synchronize` and 94 to 146 ms for the calls to the external service; only the external timeouts take 15 s, by
design. WireMock alone answers that rate in about 1 ms. Profiled with JFR, most of the CPU left is the encoding of
transcodings and synchronizations, which cannot be avoided.

## Security

- **Errors**: problem+json with messages written for clients; no stack trace, SQL, storage key or upstream body is
  exposed; unexpected errors are logged with their correlation id.
- **Input validation**: UUIDs for every identifier, a restricted charset for fixed sound names (they become storage
  key parts), bounded templates and names, Bean Validation, unknown JSON properties rejected, prepared SQL only.
- **Untrusted media**: FFmpeg only reads from an in-memory callback (no file or network protocol), with the demuxer
  forced to the declared format; magic bytes are checked first; decoded duration, upload size and idle times are capped.
  Content with no decodable audio is rejected (`422`), and every original stored from the external service is at
  least demuxed: nothing is stored that FFmpeg cannot read.
- **HTTP**: `nosniff`, `X-Frame-Options`, a `default-src 'none'` CSP, `no-store`, `Referrer-Policy`, HSTS in prod;
  CORS disabled; outgoing calls with timeouts and no redirects; secrets only from the environment.
- **Supply chain**: `mvn verify -Psecurity` produces a CycloneDX SBOM and runs the OWASP dependency check
  (`NVD_API_KEY` recommended); scan the image with `trivy image localhost/audio-demo:1.0.0-SNAPSHOT`.
- Authentication is out of scope for the demo; `quarkus-oidc` would be the natural addition.

## Robustness

- **Fuzzing**: seeded mutations of valid samples (`AudioMutator`: bit flips, boundary bytes, truncations, duplicated
  or removed chunks, garbage after a valid header, foreign headers, and WAV/MP3/AMR header fields such as rates,
  channels, sizes, frame types). `JavaCvAudioProcessorFuzzTest` runs every FFmpeg path on them (decode and encode,
  demux only, concatenation) with a timeout, then checks native memory; `UntrustedAudioFuzzTest` goes through the
  API, for uploads and for external bodies served by WireMock. Every result is valid audio or a `422`: never a `5xx`, a
  hang or something stored that is not audio. A failure prints its seed and index; the case being run is written to
  `target/fuzz/<test>.case` and removed at the end, so a file left behind names the input of a JVM crash in FFmpeg.
  `mvn verify` runs one case per mutation strategy and format; `mvn verify -Pfuzz` runs only the fuzz tests, with
  2000 and 500 cases per format, in about 15 minutes on 32 threads (override with `-Dfuzz.cases=N -Dfuzz.http.cases=N -Dfuzz.seed=S`).
- **Leaks**: `JavaCvAudioProcessorLeakTest` repeats jobs, failing ones included, and checks native memory and the
  stream registries of JavaCV. JavaCV keeps every stream in a static map until release; when FFmpeg cannot open an
  input, JavaCV 1.5.11 can no longer remove its entry (the key is zeroed by FFmpeg), so the processor removes those dead
  entries itself.
- **Soak**: under the benchmark load, repeated rounds keep the live heap after GC, the threads and the file
  descriptors flat, leave no multipart upload in progress and no stored object without its row. The resident memory
  of the process grows over the first rounds because glibc keeps freed memory in its arenas (up to 8 per CPU);
  `MALLOC_ARENA_MAX=2` caps it (RSS flat at ~890 MB from the third round) at the cost of higher latencies under load.

## Audio samples

All samples are in the public domain: twelve LibriVox poem recordings (different readers, 33 s to 96 s, like voice
messages) and four NASA Space Shuttle radio calls as fixed sounds. See the `ATTRIBUTION.md` files under
`src/test/resources`.

## License

[MIT](LICENSE)
