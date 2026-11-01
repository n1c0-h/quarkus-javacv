package com.example.audio.domain.ports.output;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.audio.domain.models.AudioFormat;

/**
 * Audio decoding and encoding on streams: nothing is fully buffered. Blocking.
 * Inputs are always read to their end, and content that does not match its declared format is rejected.
 */
public interface AudioProcessor {

    /**
     * Decodes the input once, encoding it on the fly in every requested format. Without output, the input is only
     * demuxed: nothing is decoded.
     *
     * @return the duration of the input, to the codec frame (some 20 ms)
     */
    Duration process(InputStream input, AudioFormat format, Map<AudioFormat, OutputStream> outputs);

    /**
     * Plays the parts one after the other into a single output; each part is read when it is reached. The input,
     * {@code parts[inputIndex]}, keeps its codec parameters (e.g. its sample rate), whatever they are: the other
     * parts are adapted to them when they differ.
     */
    void concatenate(List<Supplier<InputStream>> parts, int inputIndex, AudioFormat format, OutputStream output);
}
