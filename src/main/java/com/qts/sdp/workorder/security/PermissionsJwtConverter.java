package com.qts.sdp.workorder.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns the permissions claim of an already validated JWT into granted authorities.
 * Identity providers differ in where they put permissions (Okta: {@code scp}, Entra app
 * roles: {@code roles}) and in the shape (JSON array or space-delimited string), so the claim
 * name is configured per issuer and both shapes are accepted. Values that are not work order
 * permissions are ignored, so unrelated scopes can never widen access.
 */
public class PermissionsJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String permissionsClaim;

    public PermissionsJwtConverter(String permissionsClaim) {
        this.permissionsClaim = permissionsClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = rawValues(jwt.getClaim(permissionsClaim)).stream()
                .flatMap(value -> Permission.parse(value).stream())
                .distinct()
                .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(permission.asAuthority()))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static List<String> rawValues(Object claim) {
        if (claim instanceof String text) {
            return Arrays.asList(text.trim().split("\\s+"));
        }
        if (claim instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }
}
