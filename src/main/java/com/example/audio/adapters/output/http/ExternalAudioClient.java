package com.example.audio.adapters.output.http;

import java.io.InputStream;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.example.audio.domain.exceptions.AudioNotFoundException;
import com.example.audio.domain.exceptions.ExternalServiceException;
import com.example.audio.domain.models.AudioFormat.MimeTypes;
import com.example.audio.domain.models.ExternalAudio;

import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/audios")
@RegisterRestClient(configKey = "external-audio")
public interface ExternalAudioClient {

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<ExternalAudio> getMetadata(@PathParam("id") String id);

    /** The body is read while the stream is consumed. Blocking. */
    @GET
    @Path("/{id}/content")
    @Produces({ MimeTypes.WAV, MimeTypes.MP3, MimeTypes.AMR_NB, MimeTypes.AMR_WB })
    InputStream download(@PathParam("id") String id, @QueryParam("format") String format);

    /** The upstream body is never propagated: it could leak internal details. */
    @SuppressWarnings("unused") // called by the REST client
    @ClientExceptionMapper
    static RuntimeException toException(final Response response) {
        if (response.getStatus() == 404) {
            return new AudioNotFoundException("External audio not found");
        }
        return new ExternalServiceException("The external audio service answered " + response.getStatus());
    }
}
