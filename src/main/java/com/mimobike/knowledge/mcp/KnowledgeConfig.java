package com.mimobike.knowledge.mcp;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.github.GitHubClient;
import com.mimobike.knowledge.refresh.RepositoryLoader;
import com.mimobike.knowledge.refresh.RefreshService;
import com.mimobike.knowledge.search.SearchService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeConfig {

    @Bean
    public DocumentCache documentCache() {
        return new DocumentCache();
    }

    @Bean
    public RepositoryLoader repositoryLoader(GitHubClient gitHubClient) {
        return new RepositoryLoader(gitHubClient);
    }

    @Bean
    public RefreshService refreshService(KnowledgeProperties properties, GitHubClient gitHubClient,
                                         RepositoryLoader repositoryLoader, DocumentCache cache) {
        return new RefreshService(properties, gitHubClient, repositoryLoader, cache);
    }

    @Bean
    public SearchService searchService(DocumentCache cache, KnowledgeProperties properties) {
        return new SearchService(cache, properties);
    }

    @Bean
    public KnowledgeTools knowledgeTools(DocumentCache cache, SearchService searchService,
                                         KnowledgeProperties properties) {
        return new KnowledgeTools(cache, searchService, properties);
    }

    @Bean
    public ToolCallbackProvider knowledgeToolCallbacks(KnowledgeTools knowledgeTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeTools)
                .build();
    }
}
