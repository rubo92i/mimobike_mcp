package com.mimobike.knowledge.refresh;

import com.mimobike.knowledge.config.KnowledgeProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the cache asynchronously on startup (so the app comes up even when
 * GitHub is down) and refreshes it on the configured 1–5 minute interval.
 */
@Component
@ConditionalOnProperty(name = "mimo.knowledge.scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final RefreshService refreshService;
    private final KnowledgeProperties properties;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "knowledge-refresh");
        t.setDaemon(true);
        return t;
    });

    public RefreshScheduler(RefreshService refreshService, KnowledgeProperties properties) {
        this.refreshService = refreshService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long intervalMillis = properties.refreshInterval().toMillis();
        log.info("Documentation refresh scheduled every {} for {} repositories",
                properties.refreshInterval(), properties.repositories().size());
        executor.execute(this::safeRefreshAll);
        executor.scheduleWithFixedDelay(this::safeRefreshAll,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void safeRefreshAll() {
        try {
            refreshService.refreshAll();
        } catch (RuntimeException e) {
            log.error("Unexpected refresh failure: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
