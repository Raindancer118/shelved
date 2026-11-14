package de.raindancer118.shelved.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("shelved")
public record ShelvedProperties(Path dataDir, String authMode, Anthropic anthropic, int scansPerHour, String googleBooksApiKey,
                                String hardcoverApiToken, String publicUrl) {

    public record Anthropic(String apiKey, String model) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** Dev mode: everyone is the user "local", no login. */
    public boolean localAuth() {
        return "local".equalsIgnoreCase(authMode);
    }

    /** "oidc" offers the SSO portal next to own accounts; "accounts" has own accounts only. */
    public boolean oidcAuth() {
        return "oidc".equalsIgnoreCase(authMode);
    }
}
