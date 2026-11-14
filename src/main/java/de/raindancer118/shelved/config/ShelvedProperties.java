package de.raindancer118.shelved.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("shelved")
public record ShelvedProperties(Path dataDir, String authMode, Anthropic anthropic, int scansPerHour, String googleBooksApiKey) {

    public record Anthropic(String apiKey, String model) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public boolean localAuth() {
        return "local".equalsIgnoreCase(authMode);
    }
}
