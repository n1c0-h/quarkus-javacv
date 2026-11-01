package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/** The external audio was already synchronized. */
@StandardException
public final class AudioAlreadySynchronizedException extends DomainException {
}
