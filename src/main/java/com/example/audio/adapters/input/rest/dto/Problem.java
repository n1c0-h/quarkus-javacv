package com.example.audio.adapters.input.rest.dto;

import lombok.Builder;

/** RFC 9457 problem details, plus a stable error code and a correlation id to find the server logs. */
@Builder
public record Problem(String type, String title, int status, String detail, String code, String correlationId) {

    public static final String MEDIA_TYPE = "application/problem+json";
}
