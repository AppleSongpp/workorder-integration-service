package com.qts.sdp.workorder.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.qts.sdp.workorder.dto.CreateWorkOrderCommand;

/**
 * SHA-256 over the meaning of a create request rather than its raw bytes, so a retry that
 * only differs in JSON whitespace or field order is still recognised as the same request.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(CreateWorkOrderCommand command) {
        // sourceSystem is an enum name and cannot contain the separator, so this is unambiguous.
        String canonical = "v1\n" + command.sourceSystem().name() + "\n" + command.description();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
