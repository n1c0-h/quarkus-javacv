package com.example.audio.domain.models;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Audio formats understood by the service. Only the {@link #isProposed() proposed} ones are stored and served.
 */
@Getter
@RequiredArgsConstructor
public enum AudioFormat {
    WAV("wav", MimeTypes.WAV, "wav", false),
    MP3("mp3", MimeTypes.MP3, "mp3", true),
    AMR_NB("amr-nb", MimeTypes.AMR_NB, "amr", true),
    AMR_WB("amr-wb", MimeTypes.AMR_WB, "amr", true);

    /** Media types as constants, usable in annotations; the aliases are only accepted as input. */
    public static final class MimeTypes {
        public static final String WAV = "audio/wav";
        public static final String WAV_ALIAS = "audio/x-wav";
        public static final String WAVE_ALIAS = "audio/wave";
        public static final String MP3 = "audio/mpeg";
        public static final String MP3_ALIAS = "audio/mp3";
        public static final String AMR_NB = "audio/amr";
        public static final String AMR_WB = "audio/amr-wb";

        private MimeTypes() {
        }
    }

    private static final byte[] AMR_NB_MAGIC = "#!AMR\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AMR_WB_MAGIC = "#!AMR-WB\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WAVE = "WAVE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID3 = "ID3".getBytes(StandardCharsets.US_ASCII);

    /** Number of leading bytes needed by {@link #matchesSignature(byte[])}. */
    public static final int SIGNATURE_LENGTH = 12;

    private final String code;
    private final String mimeType;
    private final String extension;
    private final boolean proposed;

    public boolean isAmr() {
        return this == AMR_NB || this == AMR_WB;
    }

    public static Optional<AudioFormat> fromCode(final String code) {
        return Arrays.stream(values()).filter(f -> f.code.equalsIgnoreCase(code)).findFirst();
    }

    public static Optional<AudioFormat> fromMimeType(final String mimeType) {
        if (mimeType == null) {
            return Optional.empty();
        }
        final String base = mimeType.split(";", 2)[0].trim();
        if (base.equalsIgnoreCase(MimeTypes.WAV_ALIAS) || base.equalsIgnoreCase(MimeTypes.WAVE_ALIAS)) {
            return Optional.of(WAV);
        }
        if (base.equalsIgnoreCase(MimeTypes.MP3_ALIAS)) {
            return Optional.of(MP3);
        }
        return Arrays.stream(values()).filter(f -> f.mimeType.equalsIgnoreCase(base)).findFirst();
    }

    /**
     * Checks the container "magic bytes", so content cannot be smuggled under another declared format.
     */
    public boolean matchesSignature(final byte[] head) {
        return switch (this) {
            case WAV -> startsWith(head, 0, RIFF) && startsWith(head, 8, WAVE);
            case AMR_NB -> startsWith(head, 0, AMR_NB_MAGIC);
            case AMR_WB -> startsWith(head, 0, AMR_WB_MAGIC);
            // ID3v2 tag or a raw MPEG audio frame sync (11 set bits)
            case MP3 -> startsWith(head, 0, ID3)
                    || (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0);
        };
    }

    private static boolean startsWith(final byte[] data, final int offset, final byte[] prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        return Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
