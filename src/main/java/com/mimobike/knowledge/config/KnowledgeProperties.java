package com.mimobike.knowledge.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the documentation knowledge system. The repository list and
 * the per-repository path globs form an explicit allowlist: nothing outside it
 * is ever fetched from GitHub or exposed through MCP.
 */
@ConfigurationProperties(prefix = "mimo.knowledge")
public record KnowledgeProperties(
        Duration refreshInterval,
        Integer maxDocumentChars,
        Integer maxExcerptChars,
        GitHub github,
        List<RepositoryConfig> repositories) {

    private static final Duration MIN_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAX_INTERVAL = Duration.ofMinutes(5);

    public KnowledgeProperties {
        if (refreshInterval == null) {
            refreshInterval = Duration.ofMinutes(3);
        }
        if (refreshInterval.compareTo(MIN_INTERVAL) < 0) {
            refreshInterval = MIN_INTERVAL;
        }
        if (refreshInterval.compareTo(MAX_INTERVAL) > 0) {
            refreshInterval = MAX_INTERVAL;
        }
        if (maxDocumentChars == null || maxDocumentChars <= 0) {
            maxDocumentChars = 8000;
        }
        if (maxExcerptChars == null || maxExcerptChars <= 0) {
            maxExcerptChars = 500;
        }
        if (github == null) {
            github = new GitHub(null, null);
        }
        if (repositories == null) {
            repositories = List.of();
        }
    }

    public record GitHub(String apiBaseUrl, String token) {
        public GitHub {
            if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
                apiBaseUrl = "https://api.github.com";
            }
        }
    }

    public record RepositoryConfig(
            String service,
            String repository,
            String branch,
            List<String> allowedPaths) {

        public RepositoryConfig {
            if (branch == null || branch.isBlank()) {
                branch = "master";
            }
            if (allowedPaths == null) {
                allowedPaths = List.of();
            }
        }
    }
}
