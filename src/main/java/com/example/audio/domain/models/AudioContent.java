package com.example.audio.domain.models;

import java.io.IOException;
import java.io.OutputStream;
import java.util.OptionalLong;

import lombok.NonNull;

/**
 * An audio ready to be streamed: the bytes are only produced when it is written to a destination.
 *
 * @param length size in bytes when known up front, -1 otherwise
 */
public record AudioContent(@NonNull AudioFormat format, long length, @NonNull Writer writer) {

    @FunctionalInterface
    public interface Writer {
        void writeTo(OutputStream output) throws IOException;
    }

    public static AudioContent of(final AudioFormat format, final long length, final Writer writer) {
        return new AudioContent(format, length, writer);
    }

    public static AudioContent ofUnknownLength(final AudioFormat format, final Writer writer) {
        return new AudioContent(format, -1, writer);
    }

    public OptionalLong knownLength() {
        return length >= 0 ? OptionalLong.of(length) : OptionalLong.empty();
    }

    public void writeTo(final OutputStream output) throws IOException {
        writer.writeTo(output);
    }
}
