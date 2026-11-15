package com.qts.sdp.workorder.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.core.io.Resource;

public final class PemKeys {

    private PemKeys() {
    }

    public static RSAPublicKey readRsaPublicKey(Resource resource) {
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("RSA public key not found at " + resource
                    + ". For local use, generate the dev keys first (see README, 'Generate test tokens').");
        }
        try (InputStream in = resource.getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read RSA public key from " + resource, e);
        }
    }
}
