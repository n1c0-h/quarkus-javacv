package com.example.audio.domain.models;

import java.util.Arrays;
import java.util.HexFormat;

import com.example.audio.domain.exceptions.InfrastructureException;

/** An MD5 digest: 16 bytes, shown in hexadecimal. */
public record Md5Hash(byte[] bytes) {

    private static final int LENGTH = 16;

    public Md5Hash {
        if (bytes == null || bytes.length != LENGTH) {
            throw new InfrastructureException("Invalid MD5 hash");
        }
        bytes = bytes.clone();
    }

    public static Md5Hash of(final byte[] digest) {
        return new Md5Hash(digest);
    }

    /** A copy: the digest itself stays immutable. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Md5Hash hash && Arrays.equals(bytes, hash.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    @SuppressWarnings("NullableProblems") // never null; the project uses no @NotNull annotations
    public String toString() {
        return hex();
    }
}
