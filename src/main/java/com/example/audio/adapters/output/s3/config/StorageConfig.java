package com.example.audio.adapters.output.s3.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "audio.storage")
public interface StorageConfig {

    String bucket();

    /** Maximum wait for the client to start sending an upload; it delays some under load. */
    @WithDefault("PT1M")
    Duration uploadStartTimeout();
}
