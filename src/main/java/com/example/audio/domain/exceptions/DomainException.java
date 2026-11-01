package com.example.audio.domain.exceptions;

import lombok.experimental.StandardException;

/**
 * Base of all business errors. The message is written for clients and safe to expose; the cause never is.
 */
@StandardException
public abstract sealed class DomainException extends RuntimeException
        permits InvalidRequestException, InvalidAudioException, AudioNotFoundException, AudioAlreadySynchronizedException,
        ExternalServiceException, InfrastructureException, AudioProcessingException {
}
