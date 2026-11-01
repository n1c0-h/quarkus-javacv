package com.example.audio.adapters.input.rest;

import java.io.InputStream;

import com.example.audio.domain.exceptions.InvalidRequestException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioFormat.MimeTypes;
import com.example.audio.domain.models.FormatSelector;
import com.example.audio.domain.ports.input.AudioContentUseCase;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

/**
 * Processes an uploaded audio: FFmpeg reads the request body chunk by chunk while it writes the result to the response.
 */
@Path("/")
@RequiredArgsConstructor
public class AudioProcessingResource {

    private final AudioContentUseCase audioContent;

    @POST
    @Blocking
    @Path("/transcodings")
    @Consumes({ MimeTypes.WAV, MimeTypes.WAV_ALIAS, MimeTypes.WAVE_ALIAS, MimeTypes.MP3, MimeTypes.MP3_ALIAS, MimeTypes.AMR_NB,
            MimeTypes.AMR_WB })
    @Produces({ MimeTypes.MP3, MimeTypes.AMR_NB, MimeTypes.AMR_WB })
    public Response transcode(@HeaderParam(HttpHeaders.CONTENT_TYPE) final String contentType,
            @QueryParam("target") @DefaultValue("mp3") final String target, final InputStream body) {
        final FormatSelector selector = FormatSelector.fromCode(target);
        return AudioStreams.response(audioContent.transcode(body, format(contentType), selector), "transcoded");
    }

    /** Plays the uploaded audio inside a configured template; the result keeps the format of the upload. */
    @POST
    @Blocking
    @Path("/concatenations/{template}")
    @Consumes({ MimeTypes.MP3, MimeTypes.MP3_ALIAS, MimeTypes.AMR_NB, MimeTypes.AMR_WB })
    @Produces({ MimeTypes.MP3, MimeTypes.AMR_NB, MimeTypes.AMR_WB })
    public Response concatenate(@HeaderParam(HttpHeaders.CONTENT_TYPE) final String contentType,
            @PathParam("template") final String template, final InputStream body) {
        return AudioStreams.response(audioContent.concatenate(body, format(contentType), template), template);
    }

    private static AudioFormat format(final String contentType) {
        return AudioFormat.fromMimeType(contentType).orElseThrow(() -> new InvalidRequestException("Unsupported content type"));
    }
}
