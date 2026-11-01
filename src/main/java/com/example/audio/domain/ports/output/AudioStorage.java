package com.example.audio.domain.ports.output;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.OptionalLong;

import com.example.audio.domain.models.AudioFormat;

import io.smallrye.mutiny.Uni;

/** Binary object storage. */
public interface AudioStorage {

    /** Bytes are sent while they are written; closing the stream waits for the upload to complete. Blocking. */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // the length is known for downloads, not for encodings
    OutputStream upload(String key, AudioFormat format, OptionalLong length);

    /** Bytes arrive while the stream is read. Blocking. */
    InputStream download(String key);

    Uni<Boolean> exists(String key);

    /** Deletes the keys, ignoring the missing ones. */
    Uni<Void> delete(Collection<String> keys);
}
