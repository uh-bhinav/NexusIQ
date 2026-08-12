package com.nexusiq.common.exception;

/** A required external dependency (e.g. the AI service) failed or timed out. */
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
