package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/** Audio processing failed for a reason unrelated to the input. */
@StandardException
public final class AudioProcessingException extends DomainException {
}
