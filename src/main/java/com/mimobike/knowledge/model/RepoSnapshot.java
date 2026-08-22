package com.mimobike.knowledge.model;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of one repository's approved documentation at a specific
 * commit. Replaced atomically on refresh; never mutated.
 */
public record RepoSnapshot(
        String service,
        String repository,
        String branch,
        String commitSha,
        Instant loadedAt,
        Map<String, CachedDocument> documents) {
}
