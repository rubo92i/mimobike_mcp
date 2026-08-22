package com.mimobike.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimobike.knowledge.github.GitHubClient;
import com.mimobike.knowledge.refresh.RefreshService;
import com.mimobike.knowledge.testsupport.FakeGitHubClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Boots the real application (production application.yml, all 11 configured
 * repositories) against a fake in-memory GitHub. Verifies authentication on
 * both protected surfaces, the health/readiness contract, the reload endpoint,
 * and the MCP Streamable HTTP handshake with the actual tool set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mimo.security.auth-tokens=alice:dev-token-a,bob:dev-token-b",
        "mimo.security.reload-token=reload-token-x",
        "mimo.knowledge.scheduling-enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerIntegrationTest {

    @TestConfiguration
    static class FakeGitHubConfig {

        @Bean
        @Primary
        GitHubClient fakeGitHubClient() {
            FakeGitHubClient fake = new FakeGitHubClient();
            fake.defaultFiles = Map.of(
                    "docs/overview.md",
                    "# Overview\n\n## Purpose\nFake docs served for every repo.\n",
                    "docs/mobile-api.md",
                    "# API\n\n### POST /api/v1/example\nExample endpoint ExampleRequest.\n");
            return fake;
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RefreshService refreshService;

    @Autowired
    private GitHubClient gitHubClient;

    private FakeGitHubClient fake() {
        return (FakeGitHubClient) gitHubClient;
    }

    private HttpHeaders devHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json, text/event-stream");
        headers.setBearerAuth("dev-token-a");
        return headers;
    }

    @Test
    @Order(1)
    void healthIsOpenAndReadinessFailsBeforeLoad() {
        ResponseEntity<Map> health = rest.getForEntity("/health", Map.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).containsKeys("status", "cache");
        assertThat(health.getBody().get("status")).isEqualTo("UP");

        ResponseEntity<Map> ready = rest.getForEntity("/health/ready", Map.class);
        assertThat(ready.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @Order(2)
    void mcpEndpointRequiresDeveloperToken() {
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> without = rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>("{}", noAuth), String.class);
        assertThat(without.getStatusCode().value()).isEqualTo(401);

        HttpHeaders wrong = new HttpHeaders();
        wrong.setContentType(MediaType.APPLICATION_JSON);
        wrong.setBearerAuth("not-a-token");
        assertThat(rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>("{}", wrong), String.class).getStatusCode().value())
                .isEqualTo(401);

        // The reload token must NOT grant MCP access:
        HttpHeaders reload = new HttpHeaders();
        reload.setContentType(MediaType.APPLICATION_JSON);
        reload.setBearerAuth("reload-token-x");
        assertThat(rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>("{}", reload), String.class).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    @Order(3)
    void reloadEndpointRequiresSeparateReloadToken() {
        String body = "{\"repository\": \"rubo92i/mimobike_ipay\"}";

        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/internal/reload", HttpMethod.POST,
                new HttpEntity<>(body, noAuth), String.class).getStatusCode().value())
                .isEqualTo(401);

        // Developer tokens must NOT grant reload access:
        HttpHeaders dev = new HttpHeaders();
        dev.setContentType(MediaType.APPLICATION_JSON);
        dev.setBearerAuth("dev-token-a");
        assertThat(rest.exchange("/internal/reload", HttpMethod.POST,
                new HttpEntity<>(body, dev), String.class).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    @Order(4)
    void reloadValidatesRepositoryAllowlist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("reload-token-x");

        ResponseEntity<Map> unknown = rest.exchange("/internal/reload", HttpMethod.POST,
                new HttpEntity<>("{\"repository\": \"rubo92i/not_configured\"}", headers), Map.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<Map> missing = rest.exchange("/internal/reload", HttpMethod.POST,
                new HttpEntity<>("{}", headers), Map.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @Order(5)
    void reloadRefreshesOneRepositoryAndReportsShas() {
        refreshService.refreshAll();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("reload-token-x");
        String body = "{\"repository\": \"rubo92i/mimobike_ipay\"}";

        // Same SHA -> unchanged.
        ResponseEntity<Map> unchanged = rest.exchange("/internal/reload", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(unchanged.getStatusCode().value()).isEqualTo(200);
        assertThat(unchanged.getBody().get("changed")).isEqualTo(false);
        assertThat(unchanged.getBody().get("status")).isEqualTo("unchanged");

        // New SHA for exactly this repo -> refreshed with previous/new SHA reported.
        fake().register("rubo92i/mimobike_ipay", "bumped-sha-2", Map.of(
                "docs/overview.md", "# Overview v2\ncontent"));
        ResponseEntity<Map> changed = rest.exchange("/internal/reload", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(changed.getBody().get("changed")).isEqualTo(true);
        assertThat(changed.getBody().get("previousSha")).isEqualTo("default-sha-1");
        assertThat(changed.getBody().get("newSha")).isEqualTo("bumped-sha-2");

        // Readiness now passes and status endpoint reports repositories.
        assertThat(rest.getForEntity("/health/ready", Map.class).getStatusCode().value())
                .isEqualTo(200);
        ResponseEntity<Map> status = rest.exchange("/internal/status", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(status.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) status.getBody().get("repositories")).hasSize(11);
    }

    @Test
    @Order(6)
    void mcpHandshakeListsAndCallsTheKnowledgeTools() {
        refreshService.refreshAll();

        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26",
                  "capabilities":{},
                  "clientInfo":{"name":"integration-test","version":"1.0"}}}
                """;
        ResponseEntity<String> initResponse = rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(initialize, devHeaders()), String.class);
        assertThat(initResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(initResponse.getBody()).contains("mimo-knowledge");
        assertThat(initResponse.getBody()).contains("authoritative internal documentation");

        String sessionId = initResponse.getHeaders().getFirst("mcp-session-id");
        assertThat(sessionId).as("Mcp-Session-Id header").isNotBlank();

        HttpHeaders session = devHeaders();
        session.set("mcp-session-id", sessionId);

        rest.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session),
                String.class);

        ResponseEntity<String> toolsList = rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session),
                String.class);
        assertThat(toolsList.getStatusCode().value()).isEqualTo(200);
        assertThat(toolsList.getBody())
                .contains("list_services")
                .contains("search_docs")
                .contains("read_doc")
                .contains("\"search\"")
                .contains("\"fetch\"");

        String callListServices = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"list_services","arguments":{}}}
                """;
        ResponseEntity<String> called = rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(callListServices, session), String.class);
        assertThat(called.getStatusCode().value()).isEqualTo(200);
        assertThat(called.getBody()).contains("rubo92i/mimobike_ipay");

        String callSearch = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"search_docs","arguments":{"query":"POST /api/v1/example"}}}
                """;
        ResponseEntity<String> searched = rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(callSearch, session), String.class);
        assertThat(searched.getStatusCode().value()).isEqualTo(200);
        assertThat(searched.getBody()).contains("docs/mobile-api.md");
    }
}
