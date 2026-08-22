package com.mimobike.knowledge.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimobike.knowledge.config.KnowledgeProperties.RepositoryConfig;
import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.testsupport.FakeGitHubClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryLoaderTest {

    private final FakeGitHubClient github = new FakeGitHubClient();
    private final RepositoryLoader loader = new RepositoryLoader(github);
    private final RepositoryConfig config = new RepositoryConfig("ipay", "rubo92i/mimobike_ipay",
            "master", List.of("docs/**/*.md", "openapi/**/*.yaml"));

    @Test
    void loadsOnlyAllowlistedFilesAndNeverFetchesOthers() {
        github.register("rubo92i/mimobike_ipay", "sha1", Map.of(
                "docs/overview.md", "# Overview\ncontent",
                "docs/api/wallet.md", "# Wallet\ncontent",
                "openapi/wallet.yaml", """
                        openapi: 3.0.1
                        info: {title: Wallet}
                        paths:
                          /api/v1/wallet: {get: {summary: Balance, responses: {"200": {description: OK}}}}
                        """,
                "src/main/java/App.java", "public class App {}",
                ".env", "SECRET=real-secret-value",
                "application-prod.yml", "password: hunter2",
                "docs/notes.txt", "not markdown",
                "README.md", "# Root readme"));

        Map<String, CachedDocument> docs = loader.load(config, "sha1");

        assertThat(docs.keySet()).containsExactlyInAnyOrder(
                "docs/overview.md", "docs/api/wallet.md", "openapi/wallet.yaml");
        // Disallowed files were never even requested from GitHub:
        assertThat(github.fetchedBlobPaths).containsExactlyInAnyOrder(
                "docs/overview.md", "docs/api/wallet.md", "openapi/wallet.yaml");
        assertThat(github.fetchedBlobPaths).doesNotContain(
                ".env", "src/main/java/App.java", "application-prod.yml", "README.md");
    }

    @Test
    void recordsMetadataOnEveryDocument() {
        github.register("rubo92i/mimobike_ipay", "sha1",
                Map.of("docs/overview.md", "# Overview\ncontent"));

        CachedDocument doc = loader.load(config, "sha1").get("docs/overview.md");

        assertThat(doc.service()).isEqualTo("ipay");
        assertThat(doc.repository()).isEqualTo("rubo92i/mimobike_ipay");
        assertThat(doc.branch()).isEqualTo("master");
        assertThat(doc.commitSha()).isEqualTo("sha1");
        assertThat(doc.title()).isEqualTo("Overview");
        assertThat(doc.url()).isEqualTo(
                "https://github.com/rubo92i/mimobike_ipay/blob/sha1/docs/overview.md");
        assertThat(doc.lastRefresh()).isNotNull();
        assertThat(doc.headings()).hasSize(1);
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThat(loader.isAllowed(config, "docs/../.env")).isFalse();
        assertThat(loader.isAllowed(config, "/etc/passwd")).isFalse();
        assertThat(loader.isAllowed(config, "docs/..\\secrets.md")).isFalse();
        assertThat(loader.isAllowed(config, "c:/windows/win.md")).isFalse();
        assertThat(loader.isAllowed(config, "docs/ok.md")).isTrue();
        assertThat(loader.isAllowed(config, "docs/deep/nested/ok.md")).isTrue();
    }

    @Test
    void skipsOversizedFiles() {
        github.register("rubo92i/mimobike_ipay", "sha1", Map.of("docs/huge.md", "# H"));
        // Re-register the tree entry with a fake huge size by wrapping the fake:
        FakeGitHubClient big = new FakeGitHubClient() {
            @Override
            public java.util.List<TreeEntry> tree(String repository, String commitSha) {
                return List.of(new TreeEntry("docs/huge.md", "blob", "b1", 5_000_000));
            }
        };
        Map<String, CachedDocument> docs = new RepositoryLoader(big).load(config, "sha1");
        assertThat(docs).isEmpty();
    }
}
