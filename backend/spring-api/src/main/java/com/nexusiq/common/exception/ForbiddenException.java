package com.nexusiq.common.exception;

/** The caller is authenticated and the resource exists, but the action is not permitted. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
