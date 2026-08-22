package com.mimobike.knowledge.github;

import com.mimobike.knowledge.config.KnowledgeProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub REST API implementation. Auth uses a fine-grained read-only PAT from
 * the environment; the token is attached as a default header and never logged.
 */
public class GitHubApiClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);

    private final RestClient rest;

    public GitHubApiClient(RestClient rest) {
        this.rest = rest;
    }

    /** Applies base URL, API headers and (if present) the PAT to a builder. */
    public static RestClient configure(RestClient.Builder builder, KnowledgeProperties props) {
        builder.baseUrl(props.github().apiBaseUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "mimo-knowledge-mcp");
        String token = props.github().token();
        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token.strip());
        } else {
            log.warn("GITHUB_TOKEN is not set — only public repositories will be readable");
        }
        return builder.build();
    }

    @Override
    public String headCommitSha(String repository, String branch) {
        try {
            Map<?, ?> commit = rest.get()
                    .uri("/repos/" + repository + "/commits/" + branch)
                    .retrieve()
                    .body(Map.class);
            if (commit == null || !(commit.get("sha") instanceof String sha) || sha.isBlank()) {
                throw new GitHubException("No head commit SHA for " + repository + "@" + branch);
            }
            return sha;
        } catch (RestClientException e) {
            throw new GitHubException("Failed to resolve head of " + repository + "@" + branch
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<TreeEntry> tree(String repository, String commitSha) {
        try {
            Map<?, ?> response = rest.get()
                    .uri("/repos/" + repository + "/git/trees/" + commitSha + "?recursive=1")
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("tree") instanceof List<?> entries)) {
                throw new GitHubException("No tree in response for " + repository + "@" + commitSha);
            }
            if (Boolean.TRUE.equals(response.get("truncated"))) {
                log.warn("Tree listing for {}@{} was truncated by GitHub; some files may be missing",
                        repository, commitSha);
            }
            List<TreeEntry> result = new ArrayList<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> m
                        && m.get("path") instanceof String path
                        && m.get("type") instanceof String type
                        && m.get("sha") instanceof String sha) {
                    long size = m.get("size") instanceof Number n ? n.longValue() : 0L;
                    result.add(new TreeEntry(path, type, sha, size));
                }
            }
            return result;
        } catch (RestClientException e) {
            throw new GitHubException("Failed to list tree of " + repository + "@" + commitSha
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String blobContent(String repository, String blobSha) {
        try {
            Map<?, ?> blob = rest.get()
                    .uri("/repos/" + repository + "/git/blobs/" + blobSha)
                    .retrieve()
                    .body(Map.class);
            if (blob == null || !(blob.get("content") instanceof String content)) {
                throw new GitHubException("No content in blob " + blobSha + " of " + repository);
            }
            String encoding = blob.get("encoding") instanceof String e ? e : "base64";
            if (!"base64".equals(encoding)) {
                throw new GitHubException("Unexpected blob encoding '" + encoding
                        + "' for " + blobSha + " of " + repository);
            }
            byte[] decoded = Base64.getMimeDecoder().decode(content);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | RestClientException e) {
            throw new GitHubException("Failed to fetch blob " + blobSha + " of " + repository
                    + ": " + e.getMessage(), e);
        }
    }
}
