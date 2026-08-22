package com.mimobike.knowledge.web;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.config.KnowledgeProperties.RepositoryConfig;
import com.mimobike.knowledge.model.RefreshResult;
import com.mimobike.knowledge.model.RepoSnapshot;
import com.mimobike.knowledge.refresh.RefreshService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Targeted repository refresh for GitHub Actions (and detailed status for
 * operators). Protected by the dedicated reload token — see BearerAuthFilter.
 */
@RestController
@RequestMapping("/internal")
public class ReloadController {

    public record ReloadRequest(String repository) {
    }

    private final RefreshService refreshService;
    private final DocumentCache cache;
    private final KnowledgeProperties properties;

    public ReloadController(RefreshService refreshService, DocumentCache cache,
                            KnowledgeProperties properties) {
        this.refreshService = refreshService;
        this.cache = cache;
        this.properties = properties;
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload(@RequestBody(required = false) ReloadRequest request) {
        if (request == null || request.repository() == null || request.repository().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request body must be {\"repository\": \"owner/repo\"}"));
        }
        Optional<RepositoryConfig> config = refreshService.findByRepository(request.repository());
        if (config.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Repository is not configured for the knowledge system"));
        }

        RefreshResult result = refreshService.refreshRepository(config.get());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repository", result.repository());
        body.put("service", result.service());
        body.put("previousSha", result.previousSha());
        body.put("newSha", result.newSha());
        body.put("changed", result.changed());
        body.put("status", result.status());
        if (RefreshResult.STATUS_FAILED.equals(result.status())) {
            body.put("error", result.error());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }
        body.put("documents", result.documents());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<Map<String, Object>> repositories = new ArrayList<>();
        for (RepositoryConfig config : properties.repositories()) {
            Map<String, Object> repo = new LinkedHashMap<>();
            repo.put("service", config.service());
            repo.put("repository", config.repository());
            repo.put("branch", config.branch());
            Optional<RepoSnapshot> snapshot = cache.snapshot(config.service());
            repo.put("commitSha", snapshot.map(RepoSnapshot::commitSha).orElse(null));
            repo.put("documents", snapshot.map(s -> s.documents().size()).orElse(0));
            repo.put("loadedAt", snapshot.map(s -> s.loadedAt().toString()).orElse(null));
            cache.status(config.service()).ifPresent(s -> {
                repo.put("lastAttempt", s.lastAttempt() == null ? null : s.lastAttempt().toString());
                repo.put("lastSuccess", s.lastSuccess() == null ? null : s.lastSuccess().toString());
                repo.put("lastError", s.lastError());
            });
            repositories.add(repo);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cache", cache.state(properties.repositories().size()).name());
        body.put("repositories", repositories);
        return body;
    }
}
