package com.qts.sdp.workorder.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.qts.sdp.workorder.model.SourceSystem;

/**
 * The authenticated caller as seen by the business layer. Built exclusively from a validated
 * JWT; nothing here is taken from request parameters, headers or the request body.
 *
 * @param clientId    globally unique caller id: issuer + subject (a subject is only unique per issuer)
 * @param permissions grants extracted from the token
 */
public record Caller(String clientId, Set<Permission> permissions) {

    public static Caller from(JwtAuthenticationToken authentication) {
        String issuer = authentication.getToken().getClaimAsString("iss");
        Set<Permission> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .flatMap(authority -> Permission.parse(authority).stream())
                .collect(Collectors.toUnmodifiableSet());
        return new Caller(issuer + "|" + authentication.getToken().getSubject(), permissions);
    }

    public boolean canRead(SourceSystem sourceSystem) {
        return permissions.contains(new Permission(Permission.Action.READ, sourceSystem));
    }

    public boolean canWrite(SourceSystem sourceSystem) {
        return permissions.contains(new Permission(Permission.Action.WRITE, sourceSystem));
    }
}
