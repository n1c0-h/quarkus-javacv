# Benchmark, profiling and fuzzing report

Measurements of the production image under load, how the CPU and the memory are used, what profiling changed, and
what fuzzing found. All figures come from runs on the machine described below; they are not portable as such, but
the ratios and the conclusions are.

## Environment

| Item           | Value                                                                                                                                              |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Machine        | AMD Ryzen 9 9950X3D, 16 cores / 32 threads, 96 GB RAM, Fedora 44, rootless Podman 5.8                                                              |
| Service        | production image (`src/main/docker/Containerfile`): AlmaLinux 9 micro, jlink Java 21, FFmpeg 7.1.5 from EPEL (`ffmpeg-free`), `FFMPEG_MAX_JOBS=32` |
| Dependencies   | PostgreSQL 17, LocalStack 4.2.0 (S3), WireMock 3.13.2 (external service), on the same host (`compose.yaml` + `benchmark/compose.yaml`)             |
| Load generator | Gatling 3.16 (`benchmark/`), on the same host                                                                                                      |
| Audio          | three voice messages of 33 to 36 s, in WAV, MP3, AMR-NB and AMR-WB                                                                                 |

Everything shares the CPU: the service, its dependencies and Gatling. A toolbox container unrelated to the project
used about one core during some runs.

## Benchmark

### Workload

Open workload model: requests are sent at the target rate whatever the response times, so a slow service shows as
latency and failures, not as a lower rate. 20,000 requests per minute (333 per second) are split evenly between 12
groups, about 28 requests per second each:

| Group                                  | Requests                                                                                                                                                                 |
|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| external metadata, external content    | proxy to the external service, every offered format                                                                                                                      |
| synchronize                            | the 15 combinations of offered formats × 3 samples, fresh external ids                                                                                                   |
| get, download, rename                  | audios synchronized before the run, every format selector                                                                                                                |
| concatenate stored, concatenate upload | 3 configured templates × MP3, AMR-NB, AMR-WB                                                                                                                             |
| transcode                              | WAV, MP3, AMR-NB, AMR-WB uploads to MP3 and AMR                                                                                                                          |
| delete                                 | synchronize, delete, get (`404`)                                                                                                                                         |
| client errors                          | 15 invalid requests: ids, formats, JSON, path traversal, missing variant, `409`, `415`, `422`, unknown template…                                                         |
| external errors                        | 9 external failures: `404`, `500`, connection reset, malformed or inconsistent JSON, `503` body, broken body, wrong content type, and a 16 s answer (timeout, own group) |

Each request checks its status and its content type, size or JSON. The run passes when no request fails and every
group keeps its 99th percentile under 2 s. A full run is a 30 s ramp-up then 3 minutes at the full rate: 65,019
requests.

### Results

Final run (current code, before the last fuzzing fix, which does not change the hot paths): **65,019 requests, no
failure**. 99th percentiles per group:

| Group               | Before optimization |                                After optimization |
|---------------------|--------------------:|--------------------------------------------------:|
| get                 |                3 ms |                                              1 ms |
| client errors       |                2 ms |                                              1 ms |
| download            |               47 ms |                                             45 ms |
| rename              |               85 ms |                                             84 ms |
| external metadata   |              274 ms |                                             93 ms |
| external content    |              450 ms |                                            132 ms |
| external errors     |              373 ms |                                            120 ms |
| concatenate stored  |              385 ms |                                             59 ms |
| concatenate upload  |              382 ms |                                             55 ms |
| transcode           |              306 ms |                                            223 ms |
| synchronize         |              652 ms |                                            336 ms |
| delete (3 requests) |              627 ms |                                            358 ms |
| external timeout    |              15.3 s |          15.1 s (read timeout of 15 s, by design) |
| **all requests**    |            3,009 ms | 1,033 ms (the timeouts are 0.9 % of the requests) |

The mean throughput reported by Gatling (290 requests/s) includes the ramp-up and the end of the run; the steady rate
is 333 requests/s.

WireMock is not the bottleneck: loaded alone at 250 metadata requests/s it answers in under 1 ms at the 99th
percentile (7,500 requests, maximum 27 ms), and 100 WAV bodies of 1 MB per second in 1 ms (3,000 requests, maximum
29 ms). The latency of the external calls is the CPU shared with the FFmpeg jobs.

## CPU

### Where it goes

CPU time per container, from the cgroups, over the same 2-minute run at 20,000 requests/minute (45,014 requests):

| Container  |                Before optimization |                 After optimization |
|------------|-----------------------------------:|-----------------------------------:|
| service    | 2,686 CPU-s, 15.6 cores on average | 1,104 CPU-s, 6.4 cores (**−59 %**) |
| LocalStack |                61 CPU-s, 0.36 core |                44 CPU-s, 0.26 core |
| PostgreSQL |                13 CPU-s, 0.07 core |                10 CPU-s, 0.06 core |
| WireMock   |                 9 CPU-s, 0.05 core |                15 CPU-s, 0.08 core |

The service uses 97 % of the CPU of the stack. Inside it (JFR, `ThreadCPULoad`), before optimization: worker threads
94 %, JIT compiler 4 %, event loops 1.5 %. On the worker threads, 84 % of the samples are in native code and 16 % in
Java; 85 % of the native samples are `avcodec_send_frame`, i.e. **encoding** (decoding: 10 %, resampling: 1.4 %). The
Java part is spread out (AWS SDK request building, maps, JSON): no hot spot.

### FFmpeg builds

The FFmpeg bundled in the JavaCV jars is much slower than the distribution builds for the same codecs (a 96 s WAV,
one thread):

| Encoding | Bundled in JavaCV 1.5.11 | EPEL `ffmpeg-free` 7.1.5 | RPM Fusion `ffmpeg` 7.1.5 | Fedora 44 FFmpeg 8.1 |
|----------|-------------------------:|-------------------------:|--------------------------:|---------------------:|
| AMR-WB   |                   0.95 s |                   0.52 s |                    0.52 s |               0.39 s |
| AMR-NB   |                   0.53 s |                   0.23 s |                    0.22 s |               0.21 s |
| MP3      |                   0.29 s |                   0.27 s |                    0.27 s |              0.16 s* |

\* different settings (no resampling to 44.1 kHz).

In the service, with JavaCPP loading the EPEL libraries first (`-Dorg.bytedeco.javacpp.pathsFirst=true`), the same
WAV is transcoded to AMR-WB in 0.48 s instead of 0.95 s and to MP3 in 0.22 s instead of 0.58 s. Cost: the image grows
from 202 MB to 629 MB, mostly dependencies of the EPEL libraries unused for audio (VA-API → Mesa → LLVM, Samba).

### What profiling changed

| Finding (JFR)                                                                                                           | Change                                                                                                                                                                         | Effect                                                        |
|-------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| Concatenation: 43 % of the worker CPU, 37 % in encoding, although the output has the format of the parts                | packets of the input copied as they are; a fixed sound copied too when its codec parameters match, otherwise only that sound (1–2 s) re-encoded to the parameters of the input | concatenation ~1 % of the worker CPU; p99 385 → 59 ms         |
| Duration measured by decoding when nothing is transcoded                                                                | packets demuxed only                                                                                                                                                           | part of the 10 % of decoding                                  |
| 14.3 s of lock contention on a JVM-wide lock JavaCV takes to open and close codecs (needed before FFmpeg 4)             | JavaCV's unlocked `startUnsafe`/`releaseUnsafe` (FFmpeg 7 locks the few codecs that need it)                                                                                   | 0 (the 0.5 s left is the HTTP server binding at startup)      |
| Worker threads blocked on reactive calls during synchronizations: 4,167 thread-seconds over the run (external timeouts) | synchronization non-blocking; only the copies take worker threads                                                                                                              | 0; a slow external service no longer exhausts the worker pool |

What is left is the encoding of transcodings and synchronizations, which cannot be avoided.

## Memory

### Java heap and native memory

| Metric                           | Value                                                                                                      |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| GC pauses                        | 0.5 s in total over a 3-minute profile (G1, young collections)                                             |
| Allocation rate                  | ~400 MB/s before optimization, ~370 MB/s after (JFR samples), mostly JavaCV per-frame wrappers and buffers |
| Committed heap                   | 288 to 832 MB depending on the run (G1 sizes it; the limit is 70 % of the host memory)                     |
| Native memory tracked by the JVM | 820 MB committed at the end of the soak, heap included (NMT)                                               |

### Leak found by the soak test

Three rounds of the benchmark, sampled every 10 s. Before the fix, the live heap kept growing from round to round:

| Heap after GC   | Round 1 | Round 2 | Round 3 |
|-----------------|--------:|--------:|--------:|
| average         |   66 MB |  116 MB |  157 MB |
| last collection |   96 MB |  129 MB |  176 MB |

Cause: JavaCV registers every stream in static maps until the grabber or recorder is released.

- The processor did not release a grabber or recorder whose `start()` failed.
- When FFmpeg cannot open an input, it frees the format context and JavaCPP zeroes the address of the map key: JavaCV
  1.5.11 (and its current upstream code) can no longer remove the entry, even when released. A probe confirmed it:
  20 failed starts leave 20 entries, released or not. Each entry kept its stream and what is behind it (download,
  upload buffers).

Fix: failed starts are released at once, and the entries with a null key (only dead ones have it) are removed. The
regression test `JavaCvAudioProcessorLeakTest.forgetsTheStreamsOfFailedJobs` fails with 60 leaked entries without the
fix.

After the fix (fuzz campaign, then three rounds):

| Phase                          |  RSS max | Heap after GC, last | Threads at the end | Open files at the end |
|--------------------------------|---------:|--------------------:|-------------------:|----------------------:|
| fuzz campaign (1,320 requests) |   883 MB |              129 MB |                 86 |                   125 |
| round 1                        | 1,017 MB |              129 MB |                153 |                   447 |
| round 2                        | 1,135 MB |              116 MB |                171 |                   433 |
| round 3                        | 1,231 MB |              124 MB |                111 |                   419 |
| idle (90 s)                    | 1,231 MB |                   — |                 83 |                   124 |

- Live heap flat, threads and file descriptors back to their baseline at idle.
- No multipart upload left in progress in S3; exactly as many stored objects as variant rows (38,034).

### Resident memory and glibc arenas

The RSS still grows over the first rounds, outside the Java heap. With `MALLOC_ARENA_MAX=2` (four 2-minute rounds),
it levels off:

|                                      |  Round 1 |  Round 2 |  Round 3 | Round 4 |     Idle |
|--------------------------------------|---------:|---------:|---------:|--------:|---------:|
| RSS at the end, default arenas       | 1,017 MB | 1,135 MB | 1,231 MB |       — | 1,228 MB |
| RSS at the end, `MALLOC_ARENA_MAX=2` |   803 MB |   836 MB |   892 MB |  893 MB |   890 MB |

The growth is glibc keeping freed memory in its arenas (up to 8 per CPU), not a leak. The cap costs latency under
load, since 32 concurrent FFmpeg jobs share 2 arenas: p99 of `transcode` ~300–330 ms instead of ~220 ms, of
`synchronize` 455–611 ms instead of ~340 ms. It is not set in the image; a value between 2 and 8 is a trade-off to
measure for a given deployment.

## Fuzzing

### Method

`AudioMutator` derives inputs from valid samples with 12 seeded strategies: bit flips, boundary bytes, truncation (also
inside the header), duplicated or removed chunks, random bytes or zeros after a valid header, foreign headers,
swapped blocks, appended garbage, and format fields (WAV rates, channels, sizes; MP3 ID3 sizes and frame headers,
reserved indexes included; AMR frame types). A case is defined by its seed and index.

| Test                                              | What it runs                                                                                                                                                                                                                     | Expected                                                                               |
|---------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `JavaCvAudioProcessorFuzzTest`                    | every FFmpeg path on each input: decode and encode, demux only, concatenation (16 parallel jobs, 30 s timeout each, native memory checked at the end)                                                                            | valid output or invalid audio (`422`)                                                  |
| `UntrustedAudioFuzzTest`                          | the API: uploads (`/transcodings`, `/concatenations/{template}`) and external bodies served by WireMock (synchronization; mutated AMR next to a valid MP3, to reach the variants stored without transcoding), 4 parallel clients | `2xx` with valid audio, or `422`; every stored variant has the signature of its format |
| production image (campaign client, seed 20261102) | the same requests against the running image, with the EPEL FFmpeg                                                                                                                                                                | same                                                                                   |

The JUnit tests use the FFmpeg bundled in JavaCV, the image the EPEL one: both builds are covered. The case being run
is written to `target/fuzz/<test>.case`, so a JVM crash in FFmpeg leaves its input behind.

### Campaigns

| Campaign               | Inputs                                                                                                                                                | Result                         |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------|
| `mvn verify` (default) | 12 per format and test (one per strategy), 19 s                                                                                                       | pass                           |
| `mvn verify -Pfuzz`    | 2,000 per format for the processor (22,000 FFmpeg jobs), 500 per format through the API (3,500 uploads, 2,000 synchronizations); 13 min 45 s          | found error 4 below, then pass |
| production image       | 120 per format: 1,320 requests (transcode 430 × `200` + 50 × `422`, concatenation 330 × `200` + 30 × `422`, synchronization 430 × `201` + 50 × `422`) | pass, no `5xx`, service up     |

No case hung, crashed the JVM or kept native memory.

### Errors found

| # | Found by                                                                    | Input                                                                       | Wrong behavior                                                                                                 | Fix                                                                                            |
|---|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| 1 | processor fuzzing (seed 20261101, MP3 #6, #7, #18, #19, #30, #31, #42, #43) | MP3 with a valid ID3 tag (~335 bytes) followed by random bytes or zeros     | `500`: division by zero on a sample rate of 0 (transcoding), `avformat_write_header` error −22 (concatenation) | content with no audio parameters rejected as invalid audio (`422`)                             |
| 2 | API fuzzing (test oracle)                                                   | external AMR offered next to a valid MP3, with garbage after a valid header | stored without being parsed at all: only copied to S3                                                          | every stored original is read by FFmpeg (demux only); invalid content → `422`, uploads removed |
| 3 | soak test, then a probe                                                     | any input FFmpeg cannot open (here: external bodies broken mid-way)         | stream and buffers kept forever by JavaCV (heap leak, see Memory)                                              | failed starts released, dead registry entries removed                                          |
| 4 | long processor campaign (seed 20261101, WAV #608, #644, #1088, #1136)       | WAV header announcing a sample rate of 2,147,483,647 Hz                     | `500`: `swr_init` error −12 (the resampler cannot be set up)                                                   | sample rate above 384 kHz or more than 8 channels rejected as invalid audio (`422`)            |

Each fix has a regression test (`JavaCvAudioProcessorTest.rejectsAnAbsurdSampleRate`, the leak test, and the use-case
tests on stored originals), checked to fail without the fix.

## Limits

- The service, its dependencies and the load generator share one machine; LocalStack is not AWS S3, and WireMock
  answers far faster than a real external service would.
- Three short voice messages (33 to 36 s): longer audio costs proportionally more encoding.
- Fuzzing is mutation-based, without coverage guidance: FFmpeg's own decoders and demuxers are fuzzed with coverage
  upstream (OSS-Fuzz); these tests target the integration (error mapping, cleanup, resources, storage).
- `MALLOC_ARENA_MAX` and the size of the image (EPEL dependencies) are open trade-offs.
