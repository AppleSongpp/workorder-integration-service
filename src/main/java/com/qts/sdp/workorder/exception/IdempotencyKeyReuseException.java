package com.qts.sdp.workorder.exception;

/** The Idempotency-Key was already used by this caller for a request with a different payload. */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException() {
        super("Idempotency-Key was already used with a different request payload");
    }
}
