package com.example.audio.adapters.output.ffmpeg;

import org.assertj.core.api.Assertions;
import org.bytedeco.ffmpeg.global.avcodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the native FFmpeg build shipped by Bytedeco: the demo relies on these codecs.
 */
class FfmpegCodecAvailabilityTest {

    @ParameterizedTest
    @ValueSource(strings = {"libopencore_amrnb", "libvo_amrwbenc", "libmp3lame", "pcm_s16le"})
    void encoderIsAvailable(final String name) {
        Assertions.assertThat(avcodec.avcodec_find_encoder_by_name(name)).as("encoder %s", name).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"libopencore_amrnb", "libopencore_amrwb", "mp3float", "pcm_s16le"})
    void decoderIsAvailable(final String name) {
        Assertions.assertThat(avcodec.avcodec_find_decoder_by_name(name)).as("decoder %s", name).isNotNull();
    }
}
