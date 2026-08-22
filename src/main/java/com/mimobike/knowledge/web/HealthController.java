package com.mimobike.knowledge.web;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.cache.DocumentCache.CacheState;
import com.mimobike.knowledge.config.KnowledgeProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated liveness/readiness for load balancers. Distinguishes:
 * app running (LOADING), cache loaded (OK), GitHub temporarily unavailable but
 * stale cache serving (STALE), and no valid cache at all (EMPTY → not ready).
 * No repository names, SHAs or other internals are exposed here — detailed
 * status lives behind the reload token at /internal/status.
 */
@RestController
public class HealthController {

    private final DocumentCache cache;
    private final KnowledgeProperties properties;

    public HealthController(DocumentCache cache, KnowledgeProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        CacheState state = cache.state(properties.repositories().size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("cache", state.name());
        body.put("configuredRepositories", properties.repositories().size());
        body.put("loadedRepositories", cache.snapshots().size());
        body.put("lastSuccessfulRefresh",
                cache.lastSuccessfulRefresh().map(Instant::toString).orElse(null));
        return body;
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        CacheState state = cache.state(properties.repositories().size());
        boolean ready = state == CacheState.OK || state == CacheState.STALE;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", ready);
        body.put("cache", state.name());
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }
}
