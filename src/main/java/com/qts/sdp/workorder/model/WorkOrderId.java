package com.qts.sdp.workorder.model;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps between the numeric database key and the public identifier, e.g. {@code WO-1001}. */
public final class WorkOrderId {

    private static final String PREFIX = "WO-";
    private static final Pattern FORMAT = Pattern.compile("WO-([1-9][0-9]{0,17})");

    private WorkOrderId() {
    }

    public static String format(long id) {
        return PREFIX + id;
    }

    /** Empty when the value is not a well-formed work order id. */
    public static Optional<Long> parse(String externalId) {
        if (externalId == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(externalId);
        return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }
}
