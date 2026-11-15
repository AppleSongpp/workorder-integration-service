package com.qts.sdp.workorder.exception;

/** The work order was changed by someone else since the caller last read it. */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(long expected, long actual) {
        super("Expected version " + expected + " but work order is at version " + actual);
    }
}
