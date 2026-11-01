package com.example.audio.adapters.input.rest;

import com.example.audio.domain.exceptions.InvalidRequestException;
import com.example.audio.domain.models.AudioFormat;
import com.example.audio.domain.models.AudioFormat.MimeTypes;
import com.example.audio.domain.models.ExternalAudio;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.ports.input.ExternalAudioUseCase;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

/** Proxy to the external catalog. */
@Path("/external-audios/{externalId}")
@RequiredArgsConstructor
public class ExternalAudioResource {

    private final ExternalAudioUseCase externalAudios;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ExternalAudio> metadata(@PathParam("externalId") final String externalId) {
        return externalAudios.getMetadata(ExternalAudioId.parse(externalId));
    }

    @GET
    @Blocking
    @Path("/content")
    @Produces({ MimeTypes.WAV, MimeTypes.MP3, MimeTypes.AMR_NB, MimeTypes.AMR_WB })
    public Response content(@PathParam("externalId") final String externalId, @QueryParam("format") @DefaultValue("mp3") final String format) {
        final ExternalAudioId id = ExternalAudioId.parse(externalId);
        final AudioFormat audioFormat = AudioFormat.fromCode(format)
                .orElseThrow(() -> new InvalidRequestException("Supported formats are wav, mp3, amr-nb and amr-wb"));
        return AudioStreams.response(externalAudios.download(id, audioFormat), "external-" + id);
    }
}
