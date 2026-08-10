package com.nexusiq.common.exception;

/** Authentication itself failed (bad credentials, expired/invalid token). */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
