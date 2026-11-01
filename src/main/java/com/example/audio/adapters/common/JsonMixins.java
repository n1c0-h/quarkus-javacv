package com.example.audio.adapters.common;

import java.util.UUID;

import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.AudioVariant;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioFormat;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.models.Md5Hash;
import com.example.audio.domain.models.SoundName;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;

import io.quarkus.jackson.JacksonMixin;

/**
 * JSON shape of the domain models, kept out of the domain itself. Used by the HTTP server and by the client of the
 * external service. Value objects are written as plain values.
 */
@SuppressWarnings("unused") // mixins are discovered by Quarkus through @JacksonMixin
final class JsonMixins {

    private JsonMixins() {
    }

    @JacksonMixin(AudioId.class)
    abstract static class AudioIdMixin {
        @JsonValue
        abstract UUID value();
    }

    @JacksonMixin(ExternalAudioId.class)
    abstract static class ExternalAudioIdMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        ExternalAudioIdMixin(final UUID value) {
        }

        @JsonValue
        abstract UUID value();
    }

    @JacksonMixin(SoundName.class)
    abstract static class SoundNameMixin {
        @JsonValue
        abstract String value();
    }

    @JacksonMixin(Md5Hash.class)
    abstract static class Md5HashMixin {
        @JsonValue
        abstract String hex();
    }

    /** Also used to read the formats: an unknown one is rejected. */
    @JacksonMixin(AudioFormat.class)
    abstract static class AudioFormatMixin {
        @JsonValue
        abstract String getCode();
    }

    @JacksonMixin(Audio.class)
    @JsonPropertyOrder({ "id", "externalId", "name", "duration", "variants", "createdAt", "updatedAt" })
    abstract static class AudioMixin {
    }

    @JacksonMixin(AudioVariant.class)
    @JsonPropertyOrder({ "format", "origin", "size", "md5" })
    abstract static class AudioVariantMixin {
        /** Internal storage layout is not part of the API. */
        @JsonIgnore
        abstract String storageKey();
    }

    /** Read from the external service, which may add fields. */
    @JacksonMixin(ExternalAudio.class)
    @JsonPropertyOrder({ "id", "name", "formats" })
    @JsonIgnoreProperties(ignoreUnknown = true)
    abstract static class ExternalAudioMixin {
    }

    @JacksonMixin(ExternalAudioFormat.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    abstract static class ExternalAudioFormatMixin {
    }
}
