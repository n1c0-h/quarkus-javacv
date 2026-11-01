# JavaCV FFmpeg and Quarkus streaming: recommendations, traps and tips

Lessons from this project: audio transcoding, concatenation and duration measurement with Bytedeco JavaCV (FFmpeg),
on streams, in a Quarkus service that streams over HTTP, a REST client and S3. Every point was hit or verified here;
the class or test that implements it is given in brackets. Measurements are in the
[benchmark, profiling and fuzzing report](benchmark-and-fuzzing-report.md).

Legend: ⚠️ trap (silent wrong behavior or failure under load), ✅ recommendation, 💡 tip.

## JavaCV and FFmpeg

### Native libraries

- ⚠️ **The FFmpeg bundled in the JavaCV jars is slow for some codecs.** With JavaCV 1.5.11, AMR-WB encoding takes
  0.95 s for a 96 s WAV, against 0.52 s with the EPEL 9 build of the same FFmpeg 7.1 and 0.39 s with Fedora's;
  AMR-NB 0.53 s against 0.22 s. Measure the bundled codecs against a distribution build before sizing anything.
- ✅ **Use the system FFmpeg with `-Dorg.bytedeco.javacpp.pathsFirst=true`.** JavaCPP then loads the libraries from
  the library path first and only falls back to the bundled ones. The system FFmpeg must have the ABI the JavaCV
  bindings were built for: same major versions (`libavcodec.so.61`, `libavutil.so.59`, `libswresample.so.5` for
  FFmpeg 7.1 and JavaCV 1.5.11). Check what is really loaded in the running process: `cat /proc/1/maps | grep libav`
  [Containerfile].
- ⚠️ **Newer JavaCV natives need a newer glibc.** The releases after 1.5.11 need glibc 2.35, while EL 9 has 2.34: the
  JavaCV version is tied to the base image.
- ⚠️ **Distribution FFmpeg packages pull a lot.** On EL 9, `libavfilter` and `libavdevice` depend on Mesa, LLVM,
  Tesseract…, and `libavcodec`/`libavformat` on VA-API (→ Mesa → LLVM) and Samba: the image grew from 202 MB to 629 MB.
  💡 Install only the libraries used for audio (`libavutil`, `libswresample`, `libavcodec`, `libavformat`, `libswscale`):
  JavaCV still loads `libavfilter`, even for audio, and JavaCPP then takes the bundled one.
- ⚠️ **The non-free FFmpeg is not in EPEL**: EPEL only has `ffmpeg-free`, which still has the AMR (`libopencore_amrnb`,
  `libvo_amrwbenc`) and MP3 (`libmp3lame`) encoders. The full build is in RPM Fusion, with the same speed for these
  codecs. Both need RHEL AppStream/CRB packages that the UBI repositories do not carry: build the root filesystem from
  a binary-compatible distribution (AlmaLinux here) or with RHEL subscriptions.
- ✅ **Fail the image build when a native dependency is missing**: run the dynamic loader with `--list` on every
  bundled and system library against the final root filesystem [Containerfile].
- ✅ **Test that the encoders exist** (`avcodec_find_encoder_by_name`): a build without a codec only fails at the
  first job otherwise [`FfmpegCodecAvailabilityTest`].
- 💡 In a read-only container, JavaCPP extracts its libraries to `org.bytedeco.javacpp.cachedir`: point it to a
  writable tmpfs mounted with `exec`.
- 💡 `avutil.av_log_set_level(AV_LOG_ERROR)` keeps FFmpeg's own messages out of the logs.

### Streams in and out

- ⚠️ **`new FFmpegFrameGrabber(InputStream)` buffers the whole input**, to be able to seek. Pass
  `maximumSize = 0`: no seeking, nothing kept [`JavaCvAudioProcessor.grabber`].
- ⚠️ **Grabbers and recorders close the streams they are given by default**: `setCloseInputStream(false)` /
  `setCloseOutputStream(false)` when the caller owns them (HTTP bodies, S3 uploads).
- ⚠️ **FFmpeg takes a read error for the end of the input.** A download that breaks in the middle gives a truncated
  but "successful" result. Keep reading the input after FFmpeg is done: the failure surfaces, and the rest of the input
  is consumed (needed when a `TeeInputStream` copies it elsewhere). Some streams fail when read again after their end:
  remember the end [`EndTrackingInputStream`, `drain`].
- ✅ **Force the demuxer** (`setFormat` and the `format_whitelist` option) and **check the magic bytes before FFmpeg
  sees anything**: no format probing on untrusted content, and an early `422` for content not matching its type.
- 💡 An MP3 written to a non-seekable stream has no Xing/LAME header: players compute the duration from the frames.

### Concurrency: the global lock

- ⚠️ **JavaCV serializes every `start()` and `release()` of the JVM** on `synchronized (avcodec.class)`, a leftover of
  FFmpeg before 4.0. Under load, jobs wait for each other there (14 s of contention in a 3-minute profile here).
- ✅ FFmpeg 7 locks the few codecs whose initialization is not thread-safe itself: subclass the grabber and the recorder
  so that `start()` calls `startUnsafe()` and `release()` calls `releaseUnsafe()`; `stop()`/`close()` still do their
  full cleanup [`UnlockedGrabber`, `UnlockedRecorder`].

### Resources and leaks

- ⚠️ **A grabber or recorder whose `start()` throws is not released**, and JavaCV keeps its stream in a static map
  (`inputStreams`/`outputStreams`) until release, with everything behind it (download, upload buffers). Release in
  the failure path of `start()`.
- ⚠️ **JavaCV 1.5.11 cannot unregister a stream when FFmpeg fails to open the input** (still the case upstream): FFmpeg
  frees the format context and JavaCPP zeroes the address of the `Pointer` used as the map key, so `remove` no longer
  finds it, even after a correct release. The service removes the entries with a null key, the only dead ones
  [`UnlockedGrabber.forgetDeadStreams`]. Found by a soak test: the live heap grew by ~45 MB per round.
- ✅ **Test leaks with failing jobs**, not only successful ones: `Pointer.totalBytes()` for native memory, and the size of
  JavaCV's registries [`JavaCvAudioProcessorLeakTest`].
- 💡 **The resident memory grows over time without a leak** when many threads use FFmpeg: glibc keeps freed memory in up
  to 8 arenas per CPU. `MALLOC_ARENA_MAX` caps it (flat RSS here), at the cost of latency under load. Watch the heap
  after GC and the RSS separately before calling it a leak.
- 💡 `-Dorg.bytedeco.javacpp.maxBytes` bounds the native memory JavaCPP allocates itself.

### Validate what the header announces

- ⚠️ **A valid header with nothing decodable behind it** gives a sample rate of 0: division by zero when computing a
  duration, `avformat_write_header` error −22 when writing. Reject it as invalid audio right after `start()`.
- ⚠️ **A header can announce any sample rate**: at 2,147,483,647 Hz, `swr_init` fails with −12 (no memory). Reject rates
  and channel counts above what the service supports (384 kHz, 8 channels here).
- ✅ **Cap the duration while reading** (frame timestamps, or packet durations): a small file can announce hours.
- ✅ **Map FFmpeg failures by type**: `FrameGrabber.Exception` on the input is invalid audio (`422`); anything else is a
  processing failure (`500`). Fuzzing finds the cases where the mapping is wrong [`translate`].

### Encoding

- ⚠️ **AMR codecs accept only exact parameters**: AMR-NB 8 kHz mono at 12.2 kb/s (among the AMR-NB modes), AMR-WB
  16 kHz mono at 23.85 kb/s (among the AMR-WB modes) [`FfmpegFormatSpec`].
- ⚠️ **JavaCV only adds an audio stream when a bitrate is set**, even for PCM.
- 💡 `recordSamples(sampleRate, channels, samples)` resamples and remixes to the recorder's own format: one decoded
  frame can feed several recorders, one per output format (decode once, encode N times).
- 💡 `setSampleMode(SampleMode.SHORT)` gives interleaved 16-bit samples, the simplest to count.

### Do less: copy packets, demux only

- ✅ **Encoding is the cost** (85 % of the native CPU here). When the output has the format of the input, copy packets
  instead of decoding and encoding: `recorder.start(inputFormatContext)` then `recordPacket(packet)`
  [`JavaCvAudioProcessor.concatenate`].
- ⚠️ `recordPacket` converts the timestamps **from the time base of the stream of the context given to `start`**: give
  every packet the stream index and timestamps of that reference stream, keep the reference grabber open until the end,
  and place the packets one after the other yourself (audio has no reordering) [`Timeline`].
- ⚠️ `recordPacket` frees the packet; a packet you skip (e.g. the cover picture stream of an MP3) must be freed with
  `av_packet_unref`.
- ⚠️ In copy mode the recorder still needs a sample rate and a bitrate > 0 before `start`: placeholders, replaced by the
  parameters of the input.
- ⚠️ **Parts can only be joined by copy if they have the same codec parameters.** AMR has fixed ones; MP3 parts must
  share sample rate and channels. Do not adapt stored assets to the inputs you happen to test with: the input decides
  the parameters, and only the parts that differ (short fixed sounds) are re-encoded, in memory, to those parameters.
  Each re-encoded MP3 part adds the encoder delay (~50 ms).
- 💡 A packet without a duration: use the codec frame size (`codecpar.frame_size`, 1152 samples for MP3).
- ✅ **Measure a duration by demuxing** (sum of packet durations, to the codec frame): nearly free, and still rejects
  content FFmpeg cannot read. Validate every original you store this way, even when nothing is transcoded.

### Capacity

- ✅ **Bound the concurrent FFmpeg jobs** (SmallRye Fault Tolerance `@Bulkhead`, `503` with `Retry-After` beyond),
  configurable per deployment: `quarkus.fault-tolerance."<class>/<method>".bulkhead.value` (`FFMPEG_MAX_JOBS`).
  About one job per hardware thread.
- 💡 Profile with JFR, but read it with care: `jdk.NativeMethodSample` samples few threads, so JNI-heavy workers are
  under-represented; compare with the CPU time of the process (`/proc/<pid>/stat`) to tell CPU-bound from waiting.

## Streaming with Quarkus

### HTTP server (Quarkus REST)

- ✅ **Blocking streams on worker threads**: `@Blocking` endpoints reading the body as an `InputStream` and answering
  with a `StreamingOutput`; FFmpeg reads and writes them chunk by chunk [`AudioProcessingResource`, `AudioStreams`].
- ⚠️ **Once the first byte is sent, the status cannot change.** Do every check that can fail (lookups, existence of
  the fixed sounds, magic bytes, codec parameters) before returning the `StreamingOutput`; a failure afterwards can
  only abort the connection.
- ✅ **Declare the real media types** in `@Produces`/`@Consumes`. With a `Multi` and a wildcard, Quarkus REST picked
  server-sent events; with `StreamingOutput`, the declared types are what clients negotiate.
- ✅ Limit the body size (`quarkus.http.limits.max-body-size`); clients sending `Expect: 100-continue` get the `413`
  before uploading.
- ⚠️ **A domain exception thrown while the body is read reaches the mappers wrapped** (e.g. in a
  `BadRequestException`): look for it in the cause chain [`Problems`].

### REST client

- ✅ Return an `InputStream` for a download: the body is read while the stream is consumed.
- ✅ `@ClientExceptionMapper` to turn statuses into domain exceptions, without ever propagating the upstream body; read
  and connect timeouts; `follow-redirects: false`.
- ⚠️ An `IOException` in the middle of a downloaded body is a failure of the external service: translate it in the
  stream itself (`ProxyInputStream.handleIOException`), or it surfaces as a generic error far from its cause.

### S3 (quarkus-amazon-s3)

- ⚠️ **The CRT-based async client deadlocked when one thread fed several uploads** (request never sent within the
  timeout). The Java-based async client with multipart enabled does not
  (`builder.multipartEnabled(true)` on the builder of the extension) [`S3ClientProducer`].
- ✅ **Unknown length** (encoder output): `BlockingOutputStreamAsyncRequestBody` on the async client, with a
  `subscribeTimeout`; the SDK uploads in parts by itself. **Known length**: say it, the body is sent in one request.
- ✅ **Download**: `AsyncResponseTransformer.toBlockingInputStream()` [`S3AudioStorage`].
- 💡 An upload with a declared length fails when its source breaks ("Request content was only 0 bytes…"): expected
  after an external failure, but it adds an error of its own to the logs.

### Copy while processing

- ✅ `TeeInputStream` copies a download to its S3 upload while FFmpeg decodes it; `DigestOutputStream` and
  `CountingOutputStream` give the MD5 and the size on the way: each byte is read once [`AudioLibraryService.copy`].

### Mutiny and threads

- ⚠️ **A blocking endpoint holds a worker thread while it waits.** A synchronization waiting 15 s for a slow external
  service holds one per request: at 28 per second, the 256 default worker threads are gone and every blocking endpoint
  stalls. Keep the reactive steps reactive (checks, external metadata, database) and take worker threads only for the
  streaming work (`Uni.createFrom().item(...).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`)
  [`AudioLibraryService.synchronize`].
- ✅ `Uni.join().all(...).andCollectFailures()` waits for every parallel copy before reporting a failure, so the cleanup
  knows every upload; `onFailure().call(cleanUp)` then removes them.
- ⚠️ No `await().indefinitely()` on the event loop; in tests, `UniAssertSubscriber` with a timeout.

### Tests and Dev Services

- ✅ Dev Services for PostgreSQL (schema from `quarkus.datasource.devservices.init-script-path`), LocalStack S3 (init
  scripts seed the fixed sounds) and WireMock (stubs per test, or templated stubs).
- ⚠️ **Build-time configuration is not inherited between profiles**: `quarkus.config.profile.parent` does not carry
  Dev Services settings to the test profile; repeat them.
- ⚠️ **SELinux denies containers the Podman socket** that Testcontainers mounts into LocalStack: point
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` to an empty directory instead of relaxing SELinux.
- ⚠️ **A test bulkhead limits parallel tests too**: a synchronization takes up to two FFmpeg jobs; more parallel clients
  than the limit allows get legitimate `503`s.

### Load testing

- ✅ **Open workload** (Gatling `constantUsersPerSec`): requests arrive at the target rate whatever the response times,
  so an overloaded service shows as latency and failures.
- ⚠️ **WireMock keeps every request in its journal**: disable it (`--no-request-journal`) under load, or its memory grows
  without bound.
- 💡 Check that the mock is not the bottleneck by loading it alone; here it answered in about 1 ms at the benchmark
  rate.

## Fuzzing untrusted audio

- ✅ Mutate valid samples (bit flips, truncation, garbage after a valid header, header fields: rates, channels, sizes,
  frame types) with a seed, so any failure can be replayed; check that every path either succeeds with valid output or
  rejects the input as invalid audio, without hang or leak [`AudioMutator`, `JavaCvAudioProcessorFuzzTest`].
- ✅ Fuzz through the API too, for uploads and for external bodies: the mapping to HTTP statuses and what gets stored are
  part of the behavior [`UntrustedAudioFuzzTest`].
- ✅ Record the case being run in a file: FFmpeg runs in the JVM, and a native crash kills the test with it
  [`FuzzCaseFile`].
- 💡 Coverage-guided fuzzers for the JVM (Jazzer) do not see inside the native FFmpeg libraries; FFmpeg's own parsers
  are fuzzed with coverage upstream (OSS-Fuzz). Fuzz the integration: error mapping, cleanup, resources, storage.
- 💡 Run long campaigns separately and in parallel (`mvn verify -Pfuzz`): one of the four bugs found here only appeared
  after 600 cases per format.
