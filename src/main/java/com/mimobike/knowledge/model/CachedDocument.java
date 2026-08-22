package com.mimobike.knowledge.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CachedDocument(
        String service,
        String repository,
        String branch,
        String path,
        String type,
        String title,
        Map<String, Object> frontMatter,
        List<Heading> headings,
        List<DocSection> sections,
        String content,
        String commitSha,
        String url,
        Instant lastRefresh) {

    public static final String TYPE_MARKDOWN = "markdown";
    public static final String TYPE_OPENAPI = "openapi";
}
