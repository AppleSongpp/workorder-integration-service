package com.qts.sdp.workorder.exception;

/** Another request with the same Idempotency-Key is still being processed. Safe to retry. */
public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(Throwable cause) {
        super("A request with the same Idempotency-Key is still in progress", cause);
    }
}
