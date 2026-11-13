package com.qts.sdp.workorder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.qts.sdp.workorder.devtools.SimulatedIdp;
import com.qts.sdp.workorder.devtools.TestCaller;

/**
 * The two simulated identity providers used by all tests. Keys are generated once per JVM;
 * the public halves are written to a temp directory and handed to the application through
 * the same configuration properties a deployment would use.
 */
public final class TestIdps {

    public static final SimulatedIdp OKTA =
            SimulatedIdp.withNewKeys(SimulatedIdp.OKTA_ISSUER, SimulatedIdp.OKTA_PERMISSIONS_CLAIM);
    public static final SimulatedIdp ENTRA =
            SimulatedIdp.withNewKeys(SimulatedIdp.ENTRA_ISSUER, SimulatedIdp.ENTRA_PERMISSIONS_CLAIM);

    private static final Path OKTA_PUBLIC_KEY;
    private static final Path ENTRA_PUBLIC_KEY;

    static {
        try {
            Path directory = Files.createTempDirectory("workorders-test-keys");
            directory.toFile().deleteOnExit();
            OKTA_PUBLIC_KEY = directory.resolve("okta-public.pem");
            ENTRA_PUBLIC_KEY = directory.resolve("entra-public.pem");
            OKTA.writePublicKey(OKTA_PUBLIC_KEY);
            ENTRA.writePublicKey(ENTRA_PUBLIC_KEY);
            OKTA_PUBLIC_KEY.toFile().deleteOnExit();
            ENTRA_PUBLIC_KEY.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TestIdps() {
    }

    public static String oktaPublicKeyLocation() {
        return OKTA_PUBLIC_KEY.toUri().toString();
    }

    public static String entraPublicKeyLocation() {
        return ENTRA_PUBLIC_KEY.toUri().toString();
    }

    public static void registerKeyLocations(DynamicPropertyRegistry registry) {
        registry.add("workorders.security.issuers.okta.public-key-location", TestIdps::oktaPublicKeyLocation);
        registry.add("workorders.security.issuers.entra.public-key-location", TestIdps::entraPublicKeyLocation);
    }

    /** A valid token for one of the three callers of the exercise. */
    public static String tokenFor(TestCaller caller) {
        SimulatedIdp idp = caller.usesOkta() ? OKTA : ENTRA;
        return idp.token(caller.subject()).permissions(caller.permissions()).sign();
    }
}
