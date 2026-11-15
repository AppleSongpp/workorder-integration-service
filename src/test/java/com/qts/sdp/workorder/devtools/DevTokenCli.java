package com.qts.sdp.workorder.devtools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

/**
 * Prints an access token for one of the test callers, for use with curl against a locally
 * running service.
 *
 * <pre>
 *   mvn -q test-compile exec:java -Dexec.args="sdp"            # token valid for 60 minutes
 *   mvn -q test-compile exec:java -Dexec.args="servicenow 5"   # token valid for 5 minutes
 *   mvn -q test-compile exec:java -Dexec.args="keys"           # only create the key files
 * </pre>
 *
 * Keys live in ./dev-keys (created on first run, git-ignored). The service reads the two
 * public keys from there at startup, so run this once before starting the service.
 */
public final class DevTokenCli {

    private static final Path KEY_DIRECTORY = Path.of("dev-keys");

    private DevTokenCli() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            usage();
            return;
        }
        SimulatedIdp okta = SimulatedIdp.loadOrCreate(KEY_DIRECTORY, "okta",
                SimulatedIdp.OKTA_ISSUER, SimulatedIdp.OKTA_PERMISSIONS_CLAIM);
        SimulatedIdp entra = SimulatedIdp.loadOrCreate(KEY_DIRECTORY, "entra",
                SimulatedIdp.ENTRA_ISSUER, SimulatedIdp.ENTRA_PERMISSIONS_CLAIM);
        if (args[0].equalsIgnoreCase("keys")) {
            System.err.println("Keys are in " + KEY_DIRECTORY.toAbsolutePath());
            return;
        }

        TestCaller caller;
        long minutes;
        try {
            caller = TestCaller.valueOf(args[0].toUpperCase(Locale.ROOT));
            minutes = args.length == 2 ? Long.parseLong(args[1]) : 60;
        } catch (IllegalArgumentException e) {
            usage();
            return;
        }

        SimulatedIdp idp = caller.usesOkta() ? okta : entra;
        String token = idp.token(caller.subject())
                .permissions(caller.permissions())
                .validFor(Duration.ofMinutes(minutes))
                .sign();
        System.err.println("Token for " + caller + " (issuer " + idp.issuer() + ", valid " + minutes + " min):");
        System.out.println(token);
    }

    private static void usage() {
        System.err.println("Usage: <caller> [minutes]   where caller is one of "
                + Arrays.toString(TestCaller.values()).toLowerCase(Locale.ROOT) + ", or: keys");
    }
}
