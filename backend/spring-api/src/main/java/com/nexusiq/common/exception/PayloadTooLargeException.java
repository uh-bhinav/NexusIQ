package com.nexusiq.common.exception;

/** An upload exceeds {@code MAX_UPLOAD_MB}. */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
