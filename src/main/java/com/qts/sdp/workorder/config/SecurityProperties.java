package com.qts.sdp.workorder.config;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * @param audience required {@code aud} value of every access token
 * @param issuers  trusted token issuers, keyed by a local name used only in logs
 */
@Validated
@ConfigurationProperties("workorders.security")
public record SecurityProperties(
        @NotBlank String audience,
        @NotEmpty Map<String, @Valid Issuer> issuers) {

    /**
     * @param issuerUri         exact {@code iss} claim value
     * @param publicKeyLocation PEM-encoded RSA public key (local simulation)
     * @param jwkSetUri         JWKS endpoint (real identity provider); takes precedence over the key file
     * @param permissionsClaim  claim that carries the permissions ({@code scp} for Okta, {@code roles} for Entra)
     */
    public record Issuer(
            @NotBlank String issuerUri,
            Resource publicKeyLocation,
            String jwkSetUri,
            @NotBlank String permissionsClaim) {
    }
}
