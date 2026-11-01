package com.example.audio.domain.models;

/** Storage layout. Keys are only built from validated identifiers. */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String variant(final AudioId id, final AudioFormat format) {
        return "audios/%s/%s.%s".formatted(id, format.getCode(), format.getExtension());
    }

    public static String fixedSound(final SoundName name, final AudioFormat format) {
        return "fixed-sounds/%s/%s.%s".formatted(format.getCode(), name, format.getExtension());
    }
}
