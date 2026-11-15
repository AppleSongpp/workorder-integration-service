package com.qts.sdp.workorder.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.qts.sdp.workorder.model.SourceSystem;

class PermissionTest {

    @Test
    void parsesKnownPermissions() {
        assertThat(Permission.parse("workorders:read:SDP"))
                .contains(new Permission(Permission.Action.READ, SourceSystem.SDP));
        assertThat(Permission.parse("WorkOrders:Write:qtsos"))
                .contains(new Permission(Permission.Action.WRITE, SourceSystem.QTSOS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "openid", "workorders:write", "workorders:write:*", "workorders:admin:SDP",
            "workorders:write:SERVICENOW", "other:write:SDP", "workorders:write:SDP:extra"})
    void ignoresEverythingElse(String value) {
        assertThat(Permission.parse(value)).isEmpty();
    }

    @Test
    void authorityRoundTrips() {
        Permission permission = new Permission(Permission.Action.WRITE, SourceSystem.SDP);

        assertThat(Permission.parse(permission.asAuthority())).contains(permission);
    }
}
