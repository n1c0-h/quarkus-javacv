package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/** The storage or the database is unavailable, or holds inconsistent data. */
@StandardException
public final class InfrastructureException extends DomainException {
}
