package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/** The external audio service failed or did not answer in time. */
@StandardException
public final class ExternalServiceException extends DomainException {
}
