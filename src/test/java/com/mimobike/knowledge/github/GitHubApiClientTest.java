package com.mimobike.knowledge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mimobike.knowledge.config.KnowledgeProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubApiClientTest {

    private MockRestServiceServer server;
    private GitHubApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        KnowledgeProperties props = new KnowledgeProperties(null, null, null,
                new KnowledgeProperties.GitHub("https://api.github.test", "test-token"), null);
        client = new GitHubApiClient(GitHubApiClient.configure(builder, props));
    }

    @Test
    void resolvesHeadShaWithAuthAndApiHeaders() {
        server.expect(requestTo("https://api.github.test/repos/rubo92i/mimobike_ipay/commits/master"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("{\"sha\":\"abc123\"}", MediaType.APPLICATION_JSON));

        assertThat(client.headCommitSha("rubo92i/mimobike_ipay", "master")).isEqualTo("abc123");
        server.verify();
    }

    @Test
    void listsTreeEntries() {
        server.expect(requestTo(
                        "https://api.github.test/repos/rubo92i/mimobike_ipay/git/trees/abc123?recursive=1"))
                .andRespond(withSuccess("""
                        {"tree":[
                          {"path":"docs/overview.md","type":"blob","sha":"b1","size":10},
                          {"path":"docs","type":"tree","sha":"t1"}
                        ],"truncated":false}
                        """, MediaType.APPLICATION_JSON));

        List<GitHubClient.TreeEntry> tree = client.tree("rubo92i/mimobike_ipay", "abc123");
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).path()).isEqualTo("docs/overview.md");
        assertThat(tree.get(0).size()).isEqualTo(10);
        assertThat(tree.get(1).type()).isEqualTo("tree");
    }

    @Test
    void decodesBase64BlobContent() {
        String encoded = Base64.getMimeEncoder(60, "\n".getBytes())
                .encodeToString("# Hello\ncontent".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://api.github.test/repos/rubo92i/mimobike_ipay/git/blobs/b1"))
                .andRespond(withSuccess(
                        "{\"content\":\"" + encoded.replace("\n", "\\n") + "\",\"encoding\":\"base64\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.blobContent("rubo92i/mimobike_ipay", "b1")).isEqualTo("# Hello\ncontent");
    }

    @Test
    void wrapsHttpFailuresInGitHubException() {
        server.expect(requestTo("https://api.github.test/repos/rubo92i/mimobike_ipay/commits/master"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.headCommitSha("rubo92i/mimobike_ipay", "master"))
                .isInstanceOf(GitHubException.class);
    }
}
