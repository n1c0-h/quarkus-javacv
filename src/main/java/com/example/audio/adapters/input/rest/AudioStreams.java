package com.example.audio.adapters.input.rest;

import com.example.audio.domain.models.AudioContent;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/** Streams audio content as an HTTP response, written chunk by chunk to the client. */
final class AudioStreams {

    private AudioStreams() {
    }

    /** The file name is built from validated identifiers only, so it cannot inject header content. */
    static Response response(final AudioContent content, final String fileName) {
        final Response.ResponseBuilder response = Response.ok((StreamingOutput) content::writeTo)
                .type(content.format().getMimeType())
                .header("Content-Disposition", "attachment; filename=\"%s.%s\"".formatted(fileName, content.format().getExtension()));
        content.knownLength().ifPresent(length -> response.header(HttpHeaders.CONTENT_LENGTH, length));
        return response.build();
    }
}
