package com.qts.sdp.workorder.devtools;

import java.util.List;

/**
 * The three callers from the exercise and what each may do.
 *
 * <pre>
 * Caller       Read         Write   Issued by
 * SDP          SDP, QTSOS   SDP     simulated Okta  (permissions in "scp")
 * QTSOS        SDP, QTSOS   QTSOS   simulated Okta  (permissions in "scp")
 * ServiceNow   SDP, QTSOS   none    simulated Entra (permissions in "roles")
 * </pre>
 */
public enum TestCaller {

    SDP("sdp-service", true,
            List.of("workorders:read:SDP", "workorders:read:QTSOS", "workorders:write:SDP")),
    QTSOS("qtsos-service", true,
            List.of("workorders:read:SDP", "workorders:read:QTSOS", "workorders:write:QTSOS")),
    SERVICENOW("servicenow-service", false,
            List.of("workorders:read:SDP", "workorders:read:QTSOS"));

    private final String subject;
    private final boolean okta;
    private final List<String> permissions;

    TestCaller(String subject, boolean okta, List<String> permissions) {
        this.subject = subject;
        this.okta = okta;
        this.permissions = permissions;
    }

    public String subject() {
        return subject;
    }

    /** True if the caller's tokens come from the simulated Okta, false for the simulated Entra. */
    public boolean usesOkta() {
        return okta;
    }

    public String[] permissions() {
        return permissions.toArray(String[]::new);
    }
}
