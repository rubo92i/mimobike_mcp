package com.mimobike.knowledge.refresh;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.config.KnowledgeProperties.RepositoryConfig;
import com.mimobike.knowledge.github.GitHubClient;
import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.model.RefreshResult;
import com.mimobike.knowledge.model.RepoSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refresh coordinator. A repository is reloaded only when its head commit SHA
 * changed; failures keep the previous snapshot serving. Per-repository locks
 * make the scheduler and {@code POST /internal/reload} safe concurrently.
 */
public class RefreshService {

    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

    private final KnowledgeProperties properties;
    private final GitHubClient github;
    private final RepositoryLoader loader;
    private final DocumentCache cache;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public RefreshService(KnowledgeProperties properties, GitHubClient github,
                          RepositoryLoader loader, DocumentCache cache) {
        this.properties = properties;
        this.github = github;
        this.loader = loader;
        this.cache = cache;
    }

    public List<RefreshResult> refreshAll() {
        List<RefreshResult> results = new ArrayList<>();
        for (RepositoryConfig config : properties.repositories()) {
            results.add(refreshRepository(config));
        }
        cache.markInitialLoadCompleted();
        return results;
    }

    public RefreshResult refreshRepository(RepositoryConfig config) {
        ReentrantLock lock = locks.computeIfAbsent(config.repository(), r -> new ReentrantLock());
        lock.lock();
        try {
            String previousSha = cache.snapshot(config.service())
                    .map(RepoSnapshot::commitSha)
                    .orElse(null);
            try {
                String newSha = github.headCommitSha(config.repository(), config.branch());
                if (newSha.equals(previousSha)) {
                    cache.recordSuccess(config.service());
                    int docs = cache.snapshot(config.service())
                            .map(s -> s.documents().size()).orElse(0);
                    return RefreshResult.unchanged(config.service(), config.repository(), newSha, docs);
                }
                Map<String, CachedDocument> documents = loader.load(config, newSha);
                cache.put(new RepoSnapshot(config.service(), config.repository(), config.branch(),
                        newSha, Instant.now(), Map.copyOf(documents)));
                cache.recordSuccess(config.service());
                log.info("Refreshed {} ({} -> {}): {} documents",
                        config.repository(), shortSha(previousSha), shortSha(newSha), documents.size());
                return RefreshResult.refreshed(config.service(), config.repository(),
                        previousSha, newSha, documents.size());
            } catch (RuntimeException e) {
                // The previous snapshot (if any) keeps serving.
                cache.recordFailure(config.service(), e.getMessage());
                log.warn("Refresh of {} failed, keeping previous snapshot ({}): {}",
                        config.repository(), shortSha(previousSha), e.getMessage());
                return RefreshResult.failed(config.service(), config.repository(),
                        previousSha, e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    public Optional<RepositoryConfig> findByRepository(String repository) {
        if (repository == null) {
            return Optional.empty();
        }
        return properties.repositories().stream()
                .filter(r -> r.repository().equalsIgnoreCase(repository.strip()))
                .findFirst();
    }

    private static String shortSha(String sha) {
        return sha == null ? "none" : sha.substring(0, Math.min(sha.length(), 8));
    }
}
