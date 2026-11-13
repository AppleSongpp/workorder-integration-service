package com.qts.sdp.workorder.security;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qts.sdp.workorder.model.SourceSystem;

/**
 * A single grant: an action on the work orders of one source system.
 * Token representation: {@code workorders:<read|write>:<SOURCE_SYSTEM>}, e.g. {@code workorders:write:SDP}.
 */
public record Permission(Action action, SourceSystem sourceSystem) {

    public enum Action {
        READ,
        WRITE
    }

    private static final Pattern FORMAT = Pattern.compile("workorders:(read|write):([a-z]+)");

    /** Empty for anything that is not a work order permission this service understands. */
    public static Optional<Permission> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Action action = Action.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
        String system = matcher.group(2).toUpperCase(Locale.ROOT);
        for (SourceSystem candidate : SourceSystem.values()) {
            if (candidate.name().equals(system)) {
                return Optional.of(new Permission(action, candidate));
            }
        }
        return Optional.empty();
    }

    public String asAuthority() {
        return "workorders:" + action.name().toLowerCase(Locale.ROOT) + ":" + sourceSystem.name();
    }
}
