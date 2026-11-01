package com.example.audio.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import com.example.audio.domain.models.AudioFormat;

/**
 * Seeded mutations of valid audio files, for fuzzing. A case is fully defined by its seed and index, so any failure
 * can be replayed. The strategies go round-robin over the indexes: every strategy is exercised, whatever the count.
 */
public final class AudioMutator {

    /** Values that often reach edge cases of parsers: zero, sign changes, sizes of integer types. */
    private static final int[] INTERESTING = { 0, 1, 2, 7, 8, 16, 127, 128, 255, 256, 32_767, 65_535, 0x7FFF_FFFF, 0xFFFF_FFFF };
    private static final byte[][] FOREIGN_HEADERS = {
            "#!AMR\n".getBytes(StandardCharsets.US_ASCII), "#!AMR-WB\n".getBytes(StandardCharsets.US_ASCII),
            "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII), "ID3\4\0\0\u007F\u007F\u007F\u007F".getBytes(StandardCharsets.ISO_8859_1),
            { (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64 }, { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF } };
    private static final int STRATEGIES = 12;
    /** Below the request size limit of the test profile. */
    private static final int MAX_SIZE = 4 * 1024 * 1024;

    /** A mutated input and how it was made. */
    public record Mutation(String description, byte[] data) {
    }

    private AudioMutator() {
    }

    public static Mutation mutate(final byte[] valid, final AudioFormat format, final long seed, final int index) {
        final Random random = new Random(seed * 1_000_003L + index);
        final byte[] data = valid.clone();
        final Mutation mutation = switch (index % STRATEGIES) {
            case 0 -> bitFlips(data, random);
            case 1 -> interestingBytes(data, random);
            case 2 -> new Mutation("truncated", Arrays.copyOf(data, random.nextInt(data.length)));
            case 3 -> new Mutation("truncated in the header", Arrays.copyOf(data, random.nextInt(Math.min(64, data.length))));
            case 4 -> duplicateChunk(data, random);
            case 5 -> removeChunk(data, random);
            case 6 -> afterHeader(data, random, true);
            case 7 -> afterHeader(data, random, false);
            case 8 -> headerFields(data, format, random);
            case 9 -> foreignHeaders(data, random);
            case 10 -> swapBlocks(data, random);
            default -> appendGarbage(data, random);
        };
        return new Mutation(mutation.description(), Arrays.copyOf(mutation.data(), Math.min(mutation.data().length, MAX_SIZE)));
    }

    private static Mutation bitFlips(final byte[] data, final Random random) {
        final int flips = 1 + random.nextInt(64);
        for (int i = 0; i < flips; i++) {
            data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
        }
        return new Mutation(flips + " bit flips", data);
    }

    private static Mutation interestingBytes(final byte[] data, final Random random) {
        final int count = 1 + random.nextInt(32);
        for (int i = 0; i < count; i++) {
            data[random.nextInt(data.length)] = (byte) INTERESTING[random.nextInt(INTERESTING.length)];
        }
        return new Mutation(count + " boundary bytes", data);
    }

    private static Mutation duplicateChunk(final byte[] data, final Random random) {
        final int from = random.nextInt(data.length);
        final int length = random.nextInt(Math.min(data.length - from, 64 * 1024) + 1);
        final int at = random.nextInt(data.length);
        final byte[] result = new byte[data.length + length];
        System.arraycopy(data, 0, result, 0, at);
        System.arraycopy(data, from, result, at, length);
        System.arraycopy(data, at, result, at + length, data.length - at);
        return new Mutation("chunk of " + length + " bytes duplicated", result);
    }

    private static Mutation removeChunk(final byte[] data, final Random random) {
        final int from = random.nextInt(data.length);
        final int length = random.nextInt(Math.min(data.length - from, 64 * 1024) + 1);
        final byte[] result = new byte[data.length - length];
        System.arraycopy(data, 0, result, 0, from);
        System.arraycopy(data, from + length, result, from, data.length - from - length);
        return new Mutation("chunk of " + length + " bytes removed", result);
    }

    /** Keeps a valid beginning (the signature at least), then random bytes or zeros. */
    private static Mutation afterHeader(final byte[] data, final Random random, final boolean garbage) {
        final int kept = AudioFormat.SIGNATURE_LENGTH + random.nextInt(Math.min(512, data.length - AudioFormat.SIGNATURE_LENGTH));
        final byte[] tail = new byte[data.length - kept];
        if (garbage) {
            random.nextBytes(tail);
        }
        System.arraycopy(tail, 0, data, kept, tail.length);
        return new Mutation((garbage ? "random bytes" : "zeros") + " after " + kept + " valid bytes", data);
    }

    private static Mutation foreignHeaders(final byte[] data, final Random random) {
        final int count = 1 + random.nextInt(8);
        for (int i = 0; i < count; i++) {
            final byte[] header = FOREIGN_HEADERS[random.nextInt(FOREIGN_HEADERS.length)];
            final int at = random.nextInt(Math.max(1, data.length - header.length));
            System.arraycopy(header, 0, data, at, Math.min(header.length, data.length - at));
        }
        return new Mutation(count + " foreign headers", data);
    }

    private static Mutation swapBlocks(final byte[] data, final Random random) {
        final int size = 1 + random.nextInt(Math.min(4096, data.length / 2));
        final int a = random.nextInt(data.length - size);
        final int b = random.nextInt(data.length - size);
        final byte[] block = Arrays.copyOfRange(data, a, a + size);
        System.arraycopy(data, b, data, a, size);
        System.arraycopy(block, 0, data, b, size);
        return new Mutation("blocks of " + size + " bytes swapped", data);
    }

    private static Mutation appendGarbage(final byte[] data, final Random random) {
        final byte[] garbage = new byte[random.nextInt(512 * 1024)];
        random.nextBytes(garbage);
        final byte[] result = Arrays.copyOf(data, data.length + garbage.length);
        System.arraycopy(garbage, 0, result, data.length, garbage.length);
        return new Mutation(garbage.length + " random bytes appended", result);
    }

    /** Fields that drive the parsing and the decoding: rates, channels, sizes, frame types. */
    private static Mutation headerFields(final byte[] data, final AudioFormat format, final Random random) {
        return switch (format) {
            case WAV -> wavField(data, random);
            case MP3 -> mp3Field(data, random);
            case AMR_NB, AMR_WB -> amrFrameTypes(data, format, random);
        };
    }

    private static Mutation wavField(final byte[] data, final Random random) {
        // canonical layout of the samples: "fmt " chunk at 12, its fields from 20
        final String[] names = { "RIFF size", "fmt size", "audio format", "channels", "sample rate", "byte rate", "block align",
                "bits per sample" };
        final int[] offsets = { 4, 16, 20, 22, 24, 28, 32, 34 };
        final int[] widths = { 4, 4, 2, 2, 4, 4, 2, 2 };
        final int field = random.nextInt(offsets.length);
        final int value = INTERESTING[random.nextInt(INTERESTING.length)];
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (widths[field] == 4) {
            buffer.putInt(offsets[field], value);
        } else {
            buffer.putShort(offsets[field], (short) value);
        }
        return new Mutation("WAV " + names[field] + " = " + Integer.toUnsignedString(value), data);
    }

    private static Mutation mp3Field(final byte[] data, final Random random) {
        if (random.nextBoolean() && data.length > 10) {
            // syncsafe ID3v2 size: up to 256 MiB announced
            for (int i = 6; i < 10; i++) {
                data[i] = (byte) random.nextInt(0x80);
            }
            return new Mutation("MP3 ID3 tag size changed", data);
        }
        int patched = 0;
        for (int i = 0; i < data.length - 3 && patched < 16; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0 && random.nextInt(8) == 0) {
                // version, layer, bitrate and sample rate indexes, including the reserved values
                data[i + 1] = (byte) (0xE0 | random.nextInt(0x20));
                data[i + 2] = (byte) random.nextInt(0x100);
                patched++;
            }
        }
        return new Mutation("MP3 " + patched + " frame headers changed", data);
    }

    private static Mutation amrFrameTypes(final byte[] data, final AudioFormat format, final Random random) {
        final int start = format == AudioFormat.AMR_NB ? 6 : 9;
        final int count = 1 + random.nextInt(32);
        for (int i = 0; i < count; i++) {
            // frame type in bits 3-6 of the table of contents byte, reserved values included
            data[start + random.nextInt(data.length - start)] = (byte) ((random.nextInt(16) << 3) | 0x04);
        }
        return new Mutation("AMR " + count + " frame types changed", data);
    }
}
