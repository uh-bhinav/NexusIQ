package com.nexusiq.common.exception;

/** The request is well-formed but conflicts with existing state (e.g. duplicate email/slug). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
