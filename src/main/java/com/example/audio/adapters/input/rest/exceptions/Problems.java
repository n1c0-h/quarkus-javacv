package com.example.audio.adapters.input.rest.exceptions;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.audio.adapters.input.rest.dto.Problem;
import com.example.audio.domain.exceptions.AudioAlreadySynchronizedException;
import com.example.audio.domain.exceptions.AudioNotFoundException;
import com.example.audio.domain.exceptions.AudioProcessingException;
import com.example.audio.domain.exceptions.DomainException;
import com.example.audio.domain.exceptions.ExternalServiceException;
import com.example.audio.domain.exceptions.InfrastructureException;
import com.example.audio.domain.exceptions.InvalidAudioException;
import com.example.audio.domain.exceptions.InvalidRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns any failure into a {@link Problem}. Only messages written for clients are exposed: never stack traces,
 * exception messages of libraries or upstream bodies.
 */
@Slf4j
public final class Problems {

    private static final String INVALID_REQUEST = "INVALID_REQUEST";

    private Problems() {
    }

    public static Problem of(final Throwable t) {
        final String correlationId = UUID.randomUUID().toString();
        if (t instanceof DomainException e) {
            final int status = status(e);
            if (status >= 500) {
                log.error("Request failed [correlationId={}]", correlationId, t);
            }
            return problem(status, code(e), e.getMessage(), correlationId);
        }
        if (t instanceof ConstraintViolationException e) {
            final String detail = e.getConstraintViolations().stream()
                    .map(v -> lastNode(v.getPropertyPath().toString()) + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return problem(400, INVALID_REQUEST, detail, correlationId);
        }
        // a domain rule rejected a value while the body was read: the framework wraps it
        for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof DomainException e) {
                return of(e);
            }
        }
        if (t instanceof JsonProcessingException) {
            return problem(400, INVALID_REQUEST, "The request body is not valid JSON for this operation", correlationId);
        }
        if (t instanceof WebApplicationException e && e.getResponse().getStatus() < 500) {
            final int status = e.getResponse().getStatus();
            return problem(status, "HTTP_" + status, reasonPhrase(status), correlationId);
        }
        log.error("Unexpected failure [correlationId={}]", correlationId, t);
        return problem(500, "INTERNAL_ERROR", "An unexpected error occurred", correlationId);
    }

    static Problem busy() {
        return problem(503, "PROCESSING_BUSY", "All audio processing capacity is in use, retry later", UUID.randomUUID().toString());
    }

    static int status(final DomainException e) {
        return switch (e) {
            case InvalidRequestException ignored -> 400;
            case AudioNotFoundException ignored -> 404;
            case AudioAlreadySynchronizedException ignored -> 409;
            case InvalidAudioException ignored -> 422;
            case AudioProcessingException ignored -> 500;
            case ExternalServiceException ignored -> 502;
            case InfrastructureException ignored -> 503;
        };
    }

    /** {@code AudioNotFoundException} → {@code AUDIO_NOT_FOUND}: a stable code clients can rely on. */
    private static String code(final DomainException e) {
        return e.getClass().getSimpleName().replaceFirst("Exception$", "")
                .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String reasonPhrase(final int status) {
        final Response.Status known = Response.Status.fromStatusCode(status);
        return known != null ? known.getReasonPhrase() : "Request rejected";
    }

    private static Problem problem(final int status, final String code, final String detail, final String correlationId) {
        final String title = reasonPhrase(status);
        final String type = "urn:problem:audio-demo:" + code.toLowerCase(Locale.ROOT).replace('_', '-');
        return Problem.builder().type(type).title(title).status(status).detail(detail).code(code)
                .correlationId(correlationId).build();
    }

    /** {@code method.arg0.field} → {@code field}: parameter internals are not exposed. */
    private static String lastNode(final String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }
}
