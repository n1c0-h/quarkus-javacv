package com.example.audio.adapters.input.rest.exceptions;

import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;

import com.example.audio.adapters.input.rest.dto.Problem;
import com.example.audio.domain.exceptions.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import jakarta.validation.ValidationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Every failure becomes a problem response; the most specific mapper wins. */
@SuppressWarnings("unused") // providers discovered by JAX-RS
final class ProblemMappers {

    private ProblemMappers() {
    }

    static Response response(final Problem problem) {
        return Response.status(problem.status()).entity(problem).type(Problem.MEDIA_TYPE).build();
    }

    @Provider
    public static class DomainMapper implements ExceptionMapper<DomainException> {
        @Override
        public Response toResponse(final DomainException e) {
            return response(Problems.of(e));
        }
    }

    /** All audio processing capacity is in use: the client may retry. */
    @Provider
    public static class BulkheadMapper implements ExceptionMapper<BulkheadException> {
        @Override
        public Response toResponse(final BulkheadException e) {
            return Response.fromResponse(response(Problems.busy())).header("Retry-After", "5").build();
        }
    }

    @Provider
    public static class ValidationMapper implements ExceptionMapper<ValidationException> {
        @Override
        public Response toResponse(final ValidationException e) {
            return response(Problems.of(e));
        }
    }

    @Provider
    public static class JsonMapper implements ExceptionMapper<JsonProcessingException> {
        @Override
        public Response toResponse(final JsonProcessingException e) {
            return response(Problems.of(e));
        }
    }

    /** Replaces the built-in mapper, which echoes field names and values of the request. */
    @Provider
    public static class JsonMismatchMapper implements ExceptionMapper<MismatchedInputException> {
        @Override
        public Response toResponse(final MismatchedInputException e) {
            return response(Problems.of(e));
        }
    }

    @Provider
    public static class WebMapper implements ExceptionMapper<WebApplicationException> {
        @Override
        public Response toResponse(final WebApplicationException e) {
            return response(Problems.of(e));
        }
    }

    @Provider
    public static class UnexpectedMapper implements ExceptionMapper<Throwable> {
        @Override
        public Response toResponse(final Throwable e) {
            return response(Problems.of(e));
        }
    }
}
