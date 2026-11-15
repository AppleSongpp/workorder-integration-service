package com.qts.sdp.workorder.devtools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * A stand-in for an identity provider (Okta / Entra): owns an RSA key pair and issues RS256
 * signed access tokens. Test and local-development use only; this class is in src/test and is
 * not part of the deployable artifact.
 */
public final class SimulatedIdp {

    /** These must match workorders.security.* in application.yaml. */
    public static final String AUDIENCE = "api://workorders";
    public static final String OKTA_ISSUER = "https://okta.local.test/oauth2/workorders";
    public static final String OKTA_PERMISSIONS_CLAIM = "scp";
    public static final String ENTRA_ISSUER = "https://login.local.test/entra-tenant/v2.0";
    public static final String ENTRA_PERMISSIONS_CLAIM = "roles";

    private final String issuer;
    private final String permissionsClaim;
    private final KeyPair keyPair;

    private SimulatedIdp(String issuer, String permissionsClaim, KeyPair keyPair) {
        this.issuer = issuer;
        this.permissionsClaim = permissionsClaim;
        this.keyPair = keyPair;
    }

    /** An IdP with a fresh in-memory key pair. */
    public static SimulatedIdp withNewKeys(String issuer, String permissionsClaim) {
        return new SimulatedIdp(issuer, permissionsClaim, generateKeyPair());
    }

    /**
     * An IdP whose key pair is stored as {@code <name>-private.pem} / {@code <name>-public.pem}
     * in {@code directory}; the files are created on first use.
     */
    public static SimulatedIdp loadOrCreate(Path directory, String name, String issuer, String permissionsClaim) {
        Path privateFile = directory.resolve(name + "-private.pem");
        Path publicFile = directory.resolve(name + "-public.pem");
        try {
            if (Files.exists(privateFile) && Files.exists(publicFile)) {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(readPem(privateFile)));
                PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(readPem(publicFile)));
                return new SimulatedIdp(issuer, permissionsClaim, new KeyPair(publicKey, privateKey));
            }
            Files.createDirectories(directory);
            SimulatedIdp idp = withNewKeys(issuer, permissionsClaim);
            writePem(privateFile, "PRIVATE KEY", idp.keyPair.getPrivate().getEncoded());
            idp.writePublicKey(publicFile);
            return idp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not load keys from " + directory, e);
        }
    }

    public String issuer() {
        return issuer;
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public void writePublicKey(Path file) {
        try {
            writePem(file, "PUBLIC KEY", keyPair.getPublic().getEncoded());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Starts a token for {@code subject}; valid for one hour unless changed. */
    public TokenBuilder token(String subject) {
        return new TokenBuilder(subject);
    }

    public final class TokenBuilder {

        private final String subject;
        private String tokenIssuer = issuer;
        private String claimName = permissionsClaim;
        private List<String> permissions = List.of();
        private String audience = AUDIENCE;
        private Instant issuedAt = Instant.now();
        private Instant expiresAt = issuedAt.plus(Duration.ofHours(1));
        private PrivateKey signingKey = keyPair.getPrivate();

        private TokenBuilder(String subject) {
            this.subject = subject;
        }

        public TokenBuilder permissions(String... values) {
            this.permissions = List.of(values);
            return this;
        }

        public TokenBuilder permissionsClaim(String name) {
            this.claimName = name;
            return this;
        }

        public TokenBuilder issuer(String value) {
            this.tokenIssuer = value;
            return this;
        }

        public TokenBuilder audience(String value) {
            this.audience = value;
            return this;
        }

        public TokenBuilder validFor(Duration lifetime) {
            this.expiresAt = issuedAt.plus(lifetime);
            return this;
        }

        public TokenBuilder issuedAt(Instant value) {
            this.issuedAt = value;
            return this;
        }

        /** {@code null} produces a token without an {@code exp} claim. */
        public TokenBuilder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        public TokenBuilder signedWith(PrivateKey key) {
            this.signingKey = key;
            return this;
        }

        public JWTClaimsSet claims() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(tokenIssuer)
                    .subject(subject)
                    .audience(audience)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(issuedAt))
                    .claim(claimName, permissions);
            if (expiresAt != null) {
                claims.expirationTime(Date.from(expiresAt));
            }
            return claims.build();
        }

        public String sign() {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims());
            try {
                jwt.sign(new RSASSASigner(signingKey));
            } catch (JOSEException e) {
                throw new IllegalStateException("Could not sign token", e);
            }
            return jwt.serialize();
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readPem(Path file) throws IOException {
        String base64 = Files.readString(file, StandardCharsets.US_ASCII)
                .replaceAll("-----(BEGIN|END) [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static void writePem(Path file, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n",
                StandardCharsets.US_ASCII);
    }
}
