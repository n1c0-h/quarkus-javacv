package com.example.audio.support;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.audio.domain.models.AudioFormat;
import com.github.tomakehurst.wiremock.client.WireMock;

/** Stubs the external catalog: one audio, offered in the given formats of a sample. */
public final class ExternalCatalogStub {

    private final WireMock wiremock;

    public ExternalCatalogStub(final WireMock wiremock) {
        this.wiremock = wiremock;
    }

    public void audio(final String externalId, final String sample, final Collection<AudioFormat> formats) {
        final Map<AudioFormat, byte[]> contents = new EnumMap<>(AudioFormat.class);
        formats.forEach(format -> contents.put(format, AudioFixtures.bytes(AudioFixtures.sample(sample, format))));
        audio(externalId, sample, contents);
    }

    /** An audio whose formats have the given contents, valid or not. */
    public void audio(final String externalId, final String name, final Map<AudioFormat, byte[]> contents) {
        final String formatsJson = contents.entrySet().stream()
                .map(entry -> """
                        {"format":"%s","size":%d,"contentType":"%s"}"""
                        .formatted(entry.getKey().getCode(), entry.getValue().length, entry.getKey().getMimeType()))
                .collect(Collectors.joining(","));
        wiremock.register(WireMock.get(WireMock.urlEqualTo("/audios/" + externalId))
                .willReturn(WireMock.okJson("""
                        {"id":"%s","name":"%s","formats":[%s],"unknownField":true}""".formatted(externalId, name, formatsJson))));
        contents.forEach((format, content) -> wiremock.register(WireMock.get(WireMock.urlPathEqualTo("/audios/" + externalId + "/content"))
                .withQueryParam("format", WireMock.equalTo(format.getCode()))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", format.getMimeType())
                        .withBody(content))));
    }

    public void metadataStatus(final String externalId, final int status) {
        wiremock.register(WireMock.get(WireMock.urlEqualTo("/audios/" + externalId))
                .willReturn(WireMock.aResponse().withStatus(status).withBody("internal stack trace: secret")));
    }

    public void slowMetadata(final String externalId, final int delayMillis) {
        wiremock.register(WireMock.get(WireMock.urlEqualTo("/audios/" + externalId))
                .willReturn(WireMock.okJson("{}").withFixedDelay(delayMillis)));
    }
}
