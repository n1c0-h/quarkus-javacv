package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/** A request parameter, identifier or template is not acceptable. */
@StandardException
public final class InvalidRequestException extends DomainException {
}
