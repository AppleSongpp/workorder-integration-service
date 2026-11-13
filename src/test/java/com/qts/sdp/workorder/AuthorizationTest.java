package com.qts.sdp.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.qts.sdp.workorder.devtools.TestCaller;

/**
 * Read / write permissions and source-system restrictions.
 *
 * <pre>
 * Caller       Read         Write
 * SDP          SDP, QTSOS   SDP
 * QTSOS        SDP, QTSOS   QTSOS
 * ServiceNow   SDP, QTSOS   none
 * </pre>
 */
class AuthorizationTest extends IntegrationTest {

    @ParameterizedTest(name = "{0} creates a {1} work order -> {2}")
    @CsvSource({
            "SDP,        SDP,   201",
            "SDP,        QTSOS, 403",
            "QTSOS,      QTSOS, 201",
            "QTSOS,      SDP,   403",
            "SERVICENOW, SDP,   403",
            "SERVICENOW, QTSOS, 403"
    })
    void createFollowsWritePermissions(TestCaller caller, String sourceSystem, int expectedStatus) throws Exception {
        String description = "authz-create-" + newKey();

        postWorkOrder(bearer(caller), newKey(), createBody(sourceSystem, description))
                .andExpect(status().is(expectedStatus));

        assertThat(countWorkOrdersWithDescription(description)).isEqualTo(expectedStatus == 201 ? 1 : 0);
    }

    @ParameterizedTest(name = "{0} reads a {1} work order")
    @CsvSource({
            "SDP,        SDP",
            "SDP,        QTSOS",
            "QTSOS,      SDP",
            "QTSOS,      QTSOS",
            "SERVICENOW, SDP",
            "SERVICENOW, QTSOS"
    })
    void everyCallerCanReadBothSourceSystems(TestCaller caller, String sourceSystem) throws Exception {
        String id = givenWorkOrder(TestCaller.valueOf(sourceSystem), sourceSystem);

        getWorkOrder(bearer(caller), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.sourceSystem").value(sourceSystem));
    }

    @ParameterizedTest(name = "{0} changes the status of a {1} work order -> {2}")
    @CsvSource({
            "SDP,        SDP,   200",
            "SDP,        QTSOS, 403",
            "QTSOS,      QTSOS, 200",
            "QTSOS,      SDP,   403",
            "SERVICENOW, SDP,   403",
            "SERVICENOW, QTSOS, 403"
    })
    void statusChangeFollowsWritePermissions(TestCaller caller, String sourceSystem, int expectedStatus)
            throws Exception {
        TestCaller owner = TestCaller.valueOf(sourceSystem);
        String id = givenWorkOrder(owner, sourceSystem);

        patchStatus(caller, id, "IN_PROGRESS").andExpect(status().is(expectedStatus));

        // The source system that counts is the stored one; a denied request changes nothing.
        getWorkOrder(bearer(owner), id)
                .andExpect(jsonPath("$.status").value(expectedStatus == 200 ? "IN_PROGRESS" : "OPEN"));
    }

    @Test
    void forbiddenResponseIsGeneric() throws Exception {
        postWorkOrder(bearer(TestCaller.SERVICENOW), newKey(), createBody("SDP", "Not allowed"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value("You are not allowed to perform this operation."));
    }

    @Test
    void readIsRestrictedToGrantedSourceSystems() throws Exception {
        String sdpOnlyReader = bearer(TestIdps.OKTA.token("sdp-only-reader")
                .permissions("workorders:read:SDP").sign());
        String sdpId = givenWorkOrder(TestCaller.SDP, "SDP");
        String qtsosId = givenWorkOrder(TestCaller.QTSOS, "QTSOS");

        getWorkOrder(sdpOnlyReader, sdpId).andExpect(status().isOk());
        getWorkOrder(sdpOnlyReader, qtsosId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void writePermissionDoesNotImplyRead() throws Exception {
        String writeOnly = bearer(TestIdps.OKTA.token("sdp-writer").permissions("workorders:write:SDP").sign());
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        getWorkOrder(writeOnly, id).andExpect(status().isForbidden());
    }

    @Test
    void validTokenWithoutPermissionsCanDoNothing() throws Exception {
        String noPermissions = bearer(TestIdps.OKTA.token("nobody").sign());
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        getWorkOrder(noPermissions, id).andExpect(status().isForbidden());
        postWorkOrder(noPermissions, newKey(), createBody("SDP", "Nope")).andExpect(status().isForbidden());
        patchStatus(noPermissions, id, "{\"status\":\"IN_PROGRESS\"}").andExpect(status().isForbidden());
    }

    @Test
    void permissionsAreOnlyReadFromTheClaimConfiguredForTheIssuer() throws Exception {
        // Okta permissions live in "scp". The same values in "roles" must be ignored for Okta.
        String wrongClaim = bearer(TestIdps.OKTA.token("sdp-service")
                .permissionsClaim("roles").permissions(TestCaller.SDP.permissions()).sign());

        postWorkOrder(wrongClaim, newKey(), createBody("SDP", "Wrong claim")).andExpect(status().isForbidden());
    }

    @Test
    void unrelatedScopesGrantNothing() throws Exception {
        String token = bearer(TestIdps.OKTA.token("other-app")
                .permissions("openid", "workorders:write", "workorders:admin:SDP", "invoices:write:SDP").sign());

        postWorkOrder(token, newKey(), createBody("SDP", "Unrelated scopes")).andExpect(status().isForbidden());
    }

    @Test
    void callerControlledRequestDataCannotGrantAccess() throws Exception {
        String description = "authz-spoof-" + newKey();

        ResultActions result = mvc.perform(post("/api/workorders")
                .queryParam("sourceSystem", "SDP")
                .queryParam("caller", "sdp-service")
                .queryParam("permissions", "workorders:write:SDP")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestCaller.SERVICENOW))
                .header(IDEMPOTENCY_KEY, newKey())
                .header("X-Source-System", "SDP")
                .header("X-Caller", "sdp-service")
                .header("X-Permissions", "workorders:write:SDP")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("SDP", description)));

        result.andExpect(status().isForbidden());
        assertThat(countWorkOrdersWithDescription(description)).isZero();
    }

    @Test
    void sameSubjectFromADifferentIssuerIsADifferentCaller() throws Exception {
        // Entra issues a token whose subject equals the Okta SDP service's. It only carries the
        // permissions Entra put in it, not those of the Okta identity with the same name.
        String token = bearer(TestIdps.ENTRA.token(TestCaller.SDP.subject())
                .permissions("workorders:read:SDP").sign());

        postWorkOrder(token, newKey(), createBody("SDP", "Same subject")).andExpect(status().isForbidden());
    }
}
