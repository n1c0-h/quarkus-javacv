package com.example.audio.adapters.output.ffmpeg;

import com.example.audio.domain.models.AudioFormat;

/** FFmpeg settings per format. All outputs are mono, as voice messages are. */
record FfmpegFormatSpec(String container, String codec, int sampleRate, int bitrate) {

    static FfmpegFormatSpec of(final AudioFormat format) {
        return switch (format) {
            // JavaCV only adds an audio stream when the bitrate is set, even for PCM
            case WAV -> new FfmpegFormatSpec("wav", "pcm_s16le", 16_000, 256_000);
            case MP3 -> new FfmpegFormatSpec("mp3", "libmp3lame", 44_100, 64_000);
            // AMR codecs only accept these exact rates and bitrates
            case AMR_NB -> new FfmpegFormatSpec("amr", "libopencore_amrnb", 8_000, 12_200);
            case AMR_WB -> new FfmpegFormatSpec("amr", "libvo_amrwbenc", 16_000, 23_850);
        };
    }
}
