package com.example.audio.application.config;

import java.util.List;
import java.util.Map;

import io.smallrye.config.ConfigMapping;

/**
 * Named concatenation templates, e.g. {@code greeting: [a, INPUT, b]}: fixed sound names around exactly one
 * {@code INPUT}.
 */
@ConfigMapping(prefix = "audio.concatenation")
public interface ConcatenationConfig {

    Map<String, List<String>> templates();
}
