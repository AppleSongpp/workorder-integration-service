package com.qts.sdp.workorder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import com.nimbusds.jwt.PlainJWT;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.qts.sdp.workorder.devtools.SimulatedIdp;
import com.qts.sdp.workorder.devtools.TestCaller;

/** Missing or invalid authentication: every case must end in the same generic 401. */
class AuthenticationTest extends IntegrationTest {

    private static final String[] SDP_PERMISSIONS = TestCaller.SDP.permissions();

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        expectUnauthenticated(mvc.perform(post("/api/workorders")
                .header(IDEMPOTENCY_KEY, newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("SDP", "No token"))))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        expectUnauthenticated(mvc.perform(get("/api/workorders/WO-1001")));
        expectUnauthenticated(mvc.perform(patch("/api/workorders/WO-1001/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}")));
    }

    @Test
    void rejectsNonBearerCredentials() throws Exception {
        expectUnauthenticated(getWorkOrder("Basic c2RwOnNlY3JldA==", "WO-1001"));
    }

    @Test
    void rejectsGarbageToken() throws Exception {
        expectUnauthenticated(getWorkOrder(bearer("not-a-jwt"), "WO-1001"));
        expectUnauthenticated(getWorkOrder(bearer("aaa.bbb.ccc"), "WO-1001"));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = TestIdps.OKTA.token("sdp-service").permissions(SDP_PERMISSIONS)
                .issuedAt(Instant.now().minus(Duration.ofHours(2)))
                .expiresAt(Instant.now().minus(Duration.ofHours(1)))
                .sign();

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void rejectsTokenWithoutExpiry() throws Exception {
        String token = TestIdps.OKTA.token("sdp-service").permissions(SDP_PERMISSIONS).expiresAt(null).sign();

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void rejectsTokenForAnotherAudience() throws Exception {
        String token = TestIdps.OKTA.token("sdp-service").permissions(SDP_PERMISSIONS)
                .audience("api://some-other-service").sign();

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void rejectsTokenFromUntrustedIssuer() throws Exception {
        SimulatedIdp rogue = SimulatedIdp.withNewKeys("https://rogue.example.test", "scp");
        String token = rogue.token("sdp-service").permissions(SDP_PERMISSIONS).sign();

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void rejectsForgedSignatureForTrustedIssuer() throws Exception {
        // Claims say "issued by Okta" but the signature was made with a key Okta does not own.
        SimulatedIdp attacker = SimulatedIdp.withNewKeys(SimulatedIdp.OKTA_ISSUER, "scp");
        String token = attacker.token("sdp-service").permissions(SDP_PERMISSIONS).sign();

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void rejectsTokenSignedByOneTrustedIssuerButClaimingTheOther() throws Exception {
        // A genuine Entra key must not be able to mint tokens "from Okta".
        String token = TestIdps.OKTA.token("sdp-service").permissions(SDP_PERMISSIONS)
                .signedWith(TestIdps.ENTRA.privateKey()).sign();

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void rejectsUnsignedToken() throws Exception {
        String token = new PlainJWT(TestIdps.OKTA.token("sdp-service").permissions(SDP_PERMISSIONS).claims())
                .serialize();

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        // Take a real ServiceNow (read-only) token and swap in a payload that grants write access.
        String[] genuine = TestIdps.tokenFor(TestCaller.SERVICENOW).split("\\.");
        String forgedPayload = TestIdps.ENTRA.token("servicenow-service")
                .permissions("workorders:write:SDP").sign().split("\\.")[1];
        String anotherSignature = TestIdps.tokenFor(TestCaller.SERVICENOW).split("\\.")[2];
        String token = genuine[0] + "." + forgedPayload + "." + anotherSignature;

        expectUnauthenticated(getWorkOrder(bearer(token), "WO-1001"));
    }

    @Test
    void doesNotRevealWhyATokenWasRejected() throws Exception {
        String expired = TestIdps.OKTA.token("sdp-service").permissions(SDP_PERMISSIONS)
                .issuedAt(Instant.now().minus(Duration.ofHours(2)))
                .expiresAt(Instant.now().minus(Duration.ofHours(1)))
                .sign();

        getWorkOrder(bearer(expired), "WO-1001")
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.detail").value("A valid bearer access token is required."))
                .andExpect(content().string(not(containsString("expired"))))
                .andExpect(content().string(not(containsString("Jwt"))));
    }

    @Test
    void unknownPathsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(TestCaller.SDP)))
                .andExpect(status().isForbidden());
    }

    private static ResultActions expectUnauthenticated(ResultActions result) throws Exception {
        return result
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
