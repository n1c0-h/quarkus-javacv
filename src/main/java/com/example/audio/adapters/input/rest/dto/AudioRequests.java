package com.example.audio.adapters.input.rest.dto;

import java.util.UUID;

import com.example.audio.domain.models.Audio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies without a matching domain type (the concatenation template is read as the domain model itself). */
public final class AudioRequests {

    private AudioRequests() {
    }

    public record Synchronize(@NotNull UUID externalId) {
    }

    public record Rename(@NotBlank @Size(max = Audio.MAX_NAME_LENGTH) String name) {
    }
}
