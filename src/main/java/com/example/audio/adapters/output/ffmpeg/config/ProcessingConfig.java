package com.example.audio.adapters.output.ffmpeg.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "audio.processing")
public interface ProcessingConfig {

    /** Longer inputs are rejected while decoding. */
    @WithDefault("PT15M")
    Duration maxAudioDuration();
}
