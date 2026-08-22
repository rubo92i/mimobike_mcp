package com.mimobike.knowledge.cache;

import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.model.RepoSnapshot;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only document store in the system: an in-memory map of immutable
 * per-repository snapshots. Snapshots are replaced atomically; a failed
 * refresh never removes a working snapshot.
 */
public class DocumentCache {

    public enum CacheState { LOADING, OK, STALE, EMPTY }

    public record RepoStatus(Instant lastAttempt, Instant lastSuccess, String lastError) {
    }

    private final Map<String, RepoSnapshot> snapshotsByService = new ConcurrentHashMap<>();
    private final Map<String, RepoStatus> statusByService = new ConcurrentHashMap<>();
    private volatile boolean initialLoadCompleted;

    public void put(RepoSnapshot snapshot) {
        snapshotsByService.put(snapshot.service(), snapshot);
    }

    public Optional<RepoSnapshot> snapshot(String service) {
        return Optional.ofNullable(snapshotsByService.get(service));
    }

    public Collection<RepoSnapshot> snapshots() {
        return snapshotsByService.values();
    }

    public Optional<CachedDocument> document(String service, String path) {
        RepoSnapshot snapshot = snapshotsByService.get(service);
        if (snapshot == null || path == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.documents().get(normalizePath(path)));
    }

    public void recordSuccess(String service) {
        Instant now = Instant.now();
        statusByService.put(service, new RepoStatus(now, now, null));
    }

    public void recordFailure(String service, String error) {
        Instant now = Instant.now();
        RepoStatus previous = statusByService.get(service);
        Instant lastSuccess = previous == null ? null : previous.lastSuccess();
        statusByService.put(service, new RepoStatus(now, lastSuccess, error));
    }

    public Optional<RepoStatus> status(String service) {
        return Optional.ofNullable(statusByService.get(service));
    }

    public void markInitialLoadCompleted() {
        initialLoadCompleted = true;
    }

    public CacheState state(int configuredRepositories) {
        if (snapshotsByService.isEmpty()) {
            return initialLoadCompleted ? CacheState.EMPTY : CacheState.LOADING;
        }
        boolean incomplete = snapshotsByService.size() < configuredRepositories;
        boolean anyFailing = statusByService.values().stream()
                .anyMatch(s -> s.lastError() != null);
        return incomplete || anyFailing ? CacheState.STALE : CacheState.OK;
    }

    public Optional<Instant> lastSuccessfulRefresh() {
        return statusByService.values().stream()
                .map(RepoStatus::lastSuccess)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo);
    }

    /** Normalizes client-supplied paths into cache keys. Never touches the filesystem or GitHub. */
    public static String normalizePath(String path) {
        String p = path.strip().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
