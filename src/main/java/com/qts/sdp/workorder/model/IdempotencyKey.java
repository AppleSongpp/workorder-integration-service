package com.qts.sdp.workorder.model;

import java.util.regex.Pattern;

import com.qts.sdp.workorder.exception.InvalidIdempotencyKeyException;

/**
 * A well-formed {@code Idempotency-Key}. The format rule lives here, so an instance is always
 * valid and code that takes one never has to check it again.
 */
public record IdempotencyKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /** @throws InvalidIdempotencyKeyException if the value is not 1-128 characters of {@code A-Z a-z 0-9 . _ : -} */
    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    public static IdempotencyKey parse(String value) {
        return new IdempotencyKey(value);
    }

    /** The bare key, so it reads naturally in log lines. */
    @Override
    public String toString() {
        return value;
    }
}
