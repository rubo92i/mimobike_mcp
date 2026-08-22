package com.mimobike.knowledge.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.config.KnowledgeProperties.RepositoryConfig;
import com.mimobike.knowledge.model.RefreshResult;
import com.mimobike.knowledge.testsupport.FakeGitHubClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefreshServiceTest {

    private static final String REPO = "rubo92i/mimobike_ipay";

    private FakeGitHubClient github;
    private DocumentCache cache;
    private RefreshService service;
    private RepositoryConfig config;

    @BeforeEach
    void setUp() {
        github = new FakeGitHubClient();
        cache = new DocumentCache();
        config = new RepositoryConfig("ipay", REPO, "master", List.of("docs/**/*.md"));
        KnowledgeProperties props = new KnowledgeProperties(null, null, null, null, List.of(config));
        service = new RefreshService(props, github, new RepositoryLoader(github), cache);
    }

    @Test
    void initialLoadPopulatesCache() {
        github.register(REPO, "sha1", Map.of("docs/overview.md", "# Overview\ntext"));

        List<RefreshResult> results = service.refreshAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(RefreshResult.STATUS_REFRESHED);
        assertThat(results.get(0).changed()).isTrue();
        assertThat(results.get(0).previousSha()).isNull();
        assertThat(results.get(0).newSha()).isEqualTo("sha1");
        assertThat(cache.snapshot("ipay")).isPresent();
        assertThat(cache.document("ipay", "docs/overview.md")).isPresent();
        assertThat(cache.state(1)).isEqualTo(DocumentCache.CacheState.OK);
    }

    @Test
    void unchangedShaSkipsTreeAndBlobCalls() {
        github.register(REPO, "sha1", Map.of("docs/overview.md", "# Overview"));
        service.refreshAll();
        int treeCallsAfterLoad = github.treeCalls.get();

        RefreshResult second = service.refreshRepository(config);

        assertThat(second.status()).isEqualTo(RefreshResult.STATUS_UNCHANGED);
        assertThat(second.changed()).isFalse();
        assertThat(second.previousSha()).isEqualTo("sha1");
        assertThat(second.newSha()).isEqualTo("sha1");
        assertThat(github.treeCalls.get()).isEqualTo(treeCallsAfterLoad);
    }

    @Test
    void shaChangeReloadsRepository() {
        github.register(REPO, "sha1", Map.of("docs/overview.md", "# V1"));
        service.refreshAll();

        github.register(REPO, "sha2", Map.of(
                "docs/overview.md", "# V2",
                "docs/new.md", "# New doc"));
        RefreshResult result = service.refreshRepository(config);

        assertThat(result.status()).isEqualTo(RefreshResult.STATUS_REFRESHED);
        assertThat(result.previousSha()).isEqualTo("sha1");
        assertThat(result.newSha()).isEqualTo("sha2");
        assertThat(result.documents()).isEqualTo(2);
        assertThat(cache.document("ipay", "docs/new.md")).isPresent();
        assertThat(cache.document("ipay", "docs/overview.md").orElseThrow().content())
                .contains("# V2");
    }

    @Test
    void failedRefreshKeepsPreviousSnapshotServing() {
        github.register(REPO, "sha1", Map.of("docs/overview.md", "# V1"));
        service.refreshAll();

        github.failHead = true;
        RefreshResult result = service.refreshRepository(config);

        assertThat(result.status()).isEqualTo(RefreshResult.STATUS_FAILED);
        assertThat(result.previousSha()).isEqualTo("sha1");
        assertThat(result.error()).contains("simulated");
        // Old snapshot still serves:
        assertThat(cache.document("ipay", "docs/overview.md")).isPresent();
        assertThat(cache.state(1)).isEqualTo(DocumentCache.CacheState.STALE);

        // Recovery clears the error state:
        github.failHead = false;
        assertThat(service.refreshRepository(config).status())
                .isEqualTo(RefreshResult.STATUS_UNCHANGED);
        assertThat(cache.state(1)).isEqualTo(DocumentCache.CacheState.OK);
    }

    @Test
    void startupWithGitHubDownLeavesEmptyStateNotCrash() {
        github.failHead = true;
        List<RefreshResult> results = service.refreshAll();

        assertThat(results.get(0).status()).isEqualTo(RefreshResult.STATUS_FAILED);
        assertThat(cache.state(1)).isEqualTo(DocumentCache.CacheState.EMPTY);
    }

    @Test
    void concurrentRefreshesAreSerializedAndConsistent() throws Exception {
        github.register(REPO, "sha1", Map.of("docs/overview.md", "# V1"));
        service.refreshAll();
        github.register(REPO, "sha2", Map.of("docs/overview.md", "# V2"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RefreshResult>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.refreshRepository(config);
            }));
        }
        start.countDown();
        int refreshed = 0;
        for (Future<RefreshResult> future : futures) {
            RefreshResult result = future.get();
            assertThat(result.status()).isIn(
                    RefreshResult.STATUS_REFRESHED, RefreshResult.STATUS_UNCHANGED);
            if (RefreshResult.STATUS_REFRESHED.equals(result.status())) {
                refreshed++;
            }
        }
        pool.shutdown();
        assertThat(refreshed).isEqualTo(1);
        assertThat(cache.snapshot("ipay").orElseThrow().commitSha()).isEqualTo("sha2");
    }

    @Test
    void findByRepositoryMatchesOnlyConfiguredRepos() {
        assertThat(service.findByRepository(REPO)).isPresent();
        assertThat(service.findByRepository("RUBO92I/MIMOBIKE_IPAY")).isPresent();
        assertThat(service.findByRepository("rubo92i/other")).isEmpty();
        assertThat(service.findByRepository(null)).isEmpty();
    }
}
