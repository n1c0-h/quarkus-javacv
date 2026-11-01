package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/** The audio content cannot be used: undecodable, not matching its declared format, or too long. */
@StandardException
public final class InvalidAudioException extends DomainException {
}
