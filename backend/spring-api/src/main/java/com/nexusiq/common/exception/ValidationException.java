package com.nexusiq.common.exception;

/** Semantic validation failure not caught by Bean Validation (e.g. magic-byte mismatch). */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
