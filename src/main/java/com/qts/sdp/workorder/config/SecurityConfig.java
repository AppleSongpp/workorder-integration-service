package com.qts.sdp.workorder.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import com.qts.sdp.workorder.security.PemKeys;
import com.qts.sdp.workorder.security.PermissionsJwtConverter;
import com.qts.sdp.workorder.security.SecurityProblemWriter;

/**
 * Stateless OAuth2 resource server that trusts several token issuers (simulated Okta and Entra).
 *
 * <p>Authentication (here): the token's {@code iss} selects one of the configured issuers;
 * unknown issuers are rejected before any key is consulted. The token is then fully validated
 * with that issuer's key: RS256 signature, expiry / not-before, issuer and audience.
 *
 * <p>Authorization (business layer): the validated permissions are checked against the source
 * system of the work order being read or written, see {@code WorkOrderAccessPolicy}.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> issuerResolver,
            SecurityProblemWriter problemWriter) throws Exception {
        http
                // No cookies or sessions: every request authenticates with its bearer token.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/workorders/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(issuerResolver)
                        .authenticationEntryPoint(problemWriter)
                        .accessDeniedHandler(problemWriter))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemWriter)
                        .accessDeniedHandler(problemWriter));
        return http.build();
    }

    /** Picks the validator for a request by the (not yet trusted) {@code iss} claim of its token. */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> issuerResolver(SecurityProperties properties) {
        Map<String, AuthenticationManager> managersByIssuer = new HashMap<>();
        properties.issuers().forEach((name, issuer) -> {
            NimbusJwtDecoder decoder = decoderFor(issuer);
            decoder.setJwtValidator(tokenValidator(issuer.issuerUri(), properties.audience()));
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
            provider.setJwtAuthenticationConverter(new PermissionsJwtConverter(issuer.permissionsClaim()));
            AuthenticationManager manager = provider::authenticate;
            if (managersByIssuer.put(issuer.issuerUri(), manager) != null) {
                throw new IllegalStateException("Issuer configured twice: " + issuer.issuerUri());
            }
            log.info("Trusting token issuer name={} issuer={} permissionsClaim={}",
                    name, issuer.issuerUri(), issuer.permissionsClaim());
        });
        // Returning null for an unknown issuer makes the resolver reject the token.
        AuthenticationManagerResolver<String> byIssuer = managersByIssuer::get;
        return new JwtIssuerAuthenticationManagerResolver(byIssuer);
    }

    private static NimbusJwtDecoder decoderFor(SecurityProperties.Issuer issuer) {
        if (StringUtils.hasText(issuer.jwkSetUri())) {
            return NimbusJwtDecoder.withJwkSetUri(issuer.jwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }
        return NimbusJwtDecoder.withPublicKey(PemKeys.readRsaPublicKey(issuer.publicKeyLocation()))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuerUri, String audience) {
        return new DelegatingOAuth2TokenValidator<Jwt>(List.of(
                // exp / nbf (with 60s clock skew) and exact issuer match
                JwtValidators.createDefaultWithIssuer(issuerUri),
                // The default timestamp check accepts a token without exp; we do not.
                new JwtClaimValidator<Object>("exp", exp -> exp != null),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>("sub", StringUtils::hasText)));
    }
}
