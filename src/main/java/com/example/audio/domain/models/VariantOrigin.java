package com.example.audio.domain.models;

/** How a stored variant was obtained. */
public enum VariantOrigin {
    /** Copied as-is from the external service. */
    DOWNLOADED,
    /** Produced by the service from another format. */
    TRANSCODED
}
