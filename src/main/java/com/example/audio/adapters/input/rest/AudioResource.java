package com.example.audio.adapters.input.rest;

import java.net.URI;

import com.example.audio.adapters.input.rest.dto.AudioRequests;
import com.example.audio.domain.models.Audio;
import com.example.audio.domain.models.AudioFormat.MimeTypes;
import com.example.audio.domain.models.AudioId;
import com.example.audio.domain.models.ExternalAudioId;
import com.example.audio.domain.models.FormatSelector;
import com.example.audio.domain.ports.input.AudioContentUseCase;
import com.example.audio.domain.ports.input.AudioLibraryUseCase;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

@Path("/audios")
@RequiredArgsConstructor
public class AudioResource {

    private final AudioLibraryUseCase library;
    private final AudioContentUseCase audioContent;

    /** Non-blocking: only the copies of the external formats take worker threads. */
    @POST
    @Path("/synchronizations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> synchronize(@Valid @NotNull final AudioRequests.Synchronize request) {
        return library.synchronize(ExternalAudioId.of(request.externalId()))
                .map(audio -> Response.created(URI.create("/audios/" + audio.id())).entity(audio).build());
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Audio> get(@PathParam("id") final String id) {
        return library.get(AudioId.parse(id));
    }

    @GET
    @Blocking
    @Path("/{id}/content")
    @Produces({ MimeTypes.MP3, MimeTypes.AMR_NB, MimeTypes.AMR_WB })
    public Response content(@PathParam("id") final String id, @QueryParam("format") @DefaultValue("mp3") final String format) {
        final AudioId audioId = AudioId.parse(id);
        return AudioStreams.response(audioContent.download(audioId, FormatSelector.fromCode(format)), "audio-" + audioId);
    }

    /** Plays the stored audio inside a configured template. */
    @POST
    @Blocking
    @Path("/{id}/concatenations/{template}")
    @Produces({ MimeTypes.MP3, MimeTypes.AMR_NB, MimeTypes.AMR_WB })
    public Response concatenate(@PathParam("id") final String id, @PathParam("template") final String template,
            @QueryParam("format") @DefaultValue("mp3") final String format) {
        final AudioId audioId = AudioId.parse(id);
        return AudioStreams.response(audioContent.concatenate(audioId, FormatSelector.fromCode(format), template),
                "audio-" + audioId + "-" + template);
    }

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Audio> rename(@PathParam("id") final String id, @Valid @NotNull final AudioRequests.Rename request) {
        return library.rename(AudioId.parse(id), request.name());
    }

    @DELETE
    @Path("/{id}")
    public Uni<Response> delete(@PathParam("id") final String id) {
        return library.delete(AudioId.parse(id)).map(done -> Response.noContent().build());
    }
}
