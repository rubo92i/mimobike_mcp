package com.mimobike.knowledge.refresh;

import com.mimobike.knowledge.config.KnowledgeProperties.RepositoryConfig;
import com.mimobike.knowledge.github.GitHubClient;
import com.mimobike.knowledge.github.GitHubClient.TreeEntry;
import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.parse.MarkdownParser;
import com.mimobike.knowledge.parse.OpenApiParser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

/**
 * Loads one repository snapshot. Only tree entries matching the configured
 * allowlist globs are ever fetched from GitHub — disallowed files (source,
 * secrets, manifests) are never even requested, let alone cached.
 */
public class RepositoryLoader {

    private static final Logger log = LoggerFactory.getLogger(RepositoryLoader.class);
    private static final long MAX_FILE_BYTES = 1_000_000;

    private final GitHubClient github;
    private final AntPathMatcher matcher = new AntPathMatcher("/");

    public RepositoryLoader(GitHubClient github) {
        this.github = github;
    }

    public Map<String, CachedDocument> load(RepositoryConfig config, String commitSha) {
        Map<String, CachedDocument> documents = new LinkedHashMap<>();
        Instant now = Instant.now();

        for (TreeEntry entry : github.tree(config.repository(), commitSha)) {
            if (!"blob".equals(entry.type()) || !isAllowed(config, entry.path())) {
                continue;
            }
            if (entry.size() > MAX_FILE_BYTES) {
                log.warn("Skipping oversized file {} ({} bytes) in {}",
                        entry.path(), entry.size(), config.repository());
                continue;
            }
            CachedDocument doc = parse(config, entry, commitSha, now);
            if (doc != null) {
                documents.put(entry.path(), doc);
            }
        }
        return documents;
    }

    /**
     * A path is allowed only if it is a clean repo-relative path matching one
     * of the configured globs. Traversal fragments and absolute paths are
     * rejected outright (defense in depth — GitHub trees do not contain them).
     */
    boolean isAllowed(RepositoryConfig config, String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")
                || path.contains("\\") || path.contains(":")) {
            return false;
        }
        return config.allowedPaths().stream()
                .anyMatch(pattern -> matcher.match(pattern, path));
    }

    private CachedDocument parse(RepositoryConfig config, TreeEntry entry,
                                 String commitSha, Instant refreshedAt) {
        String fileName = entry.path().contains("/")
                ? entry.path().substring(entry.path().lastIndexOf('/') + 1)
                : entry.path();
        String url = "https://github.com/" + config.repository()
                + "/blob/" + commitSha + "/" + entry.path();
        String lower = entry.path().toLowerCase(Locale.ROOT);

        String content = github.blobContent(config.repository(), entry.sha());

        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            MarkdownParser.Parsed parsed = MarkdownParser.parse(content, fileName);
            return new CachedDocument(config.service(), config.repository(), config.branch(),
                    entry.path(), CachedDocument.TYPE_MARKDOWN, parsed.title(),
                    parsed.frontMatter(), parsed.headings(), parsed.sections(),
                    parsed.body(), commitSha, url, refreshedAt);
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            OpenApiParser.Parsed parsed = OpenApiParser.parse(content, fileName);
            if (parsed == null) {
                log.debug("Skipping {} in {}: not a parseable OpenAPI document",
                        entry.path(), config.repository());
                return null;
            }
            return new CachedDocument(config.service(), config.repository(), config.branch(),
                    entry.path(), CachedDocument.TYPE_OPENAPI, parsed.title(),
                    Map.of(), parsed.headings(), parsed.sections(),
                    parsed.body(), commitSha, url, refreshedAt);
        }
        log.debug("Skipping {} in {}: unsupported file type", entry.path(), config.repository());
        return null;
    }
}
