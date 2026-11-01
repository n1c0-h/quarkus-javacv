package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/** An audio, one of its variants, a fixed sound or an external audio does not exist. */
@StandardException
public final class AudioNotFoundException extends DomainException {
}
