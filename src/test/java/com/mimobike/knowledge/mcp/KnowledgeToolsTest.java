package com.mimobike.knowledge.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.config.KnowledgeProperties.RepositoryConfig;
import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.model.RepoSnapshot;
import com.mimobike.knowledge.parse.MarkdownParser;
import com.mimobike.knowledge.search.SearchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeToolsTest {

    private DocumentCache cache;
    private KnowledgeTools tools;

    @BeforeEach
    void setUp() {
        cache = new DocumentCache();
        KnowledgeProperties props = new KnowledgeProperties(null, 8000, 500, null, List.of(
                new RepositoryConfig("ipay", "rubo92i/mimobike_ipay", "master",
                        List.of("docs/**/*.md")),
                new RepositoryConfig("sharing", "rubo92i/mimobike_sharing", "master",
                        List.of("docs/**/*.md"))));
        tools = new KnowledgeTools(cache, new SearchService(cache, props), props);

        putDoc("ipay", "docs/mobile-api.md", """
                # Wallet API

                ## Overview
                Wallet basics.

                ### POST /api/v1/wallet/topup
                Request: WalletTopUpRequest. Errors: WALLET_LIMIT_EXCEEDED.
                """);
    }

    private void putDoc(String service, String path, String markdown) {
        MarkdownParser.Parsed parsed = MarkdownParser.parse(markdown, path);
        CachedDocument doc = new CachedDocument(service, "rubo92i/mimobike_" + service, "master",
                path, CachedDocument.TYPE_MARKDOWN, parsed.title(), parsed.frontMatter(),
                parsed.headings(), parsed.sections(), parsed.body(), "sha-1",
                "https://github.com/rubo92i/mimobike_" + service + "/blob/sha-1/" + path,
                Instant.now());
        Map<String, CachedDocument> docs = new LinkedHashMap<>();
        cache.snapshot(service).ifPresent(s -> docs.putAll(s.documents()));
        docs.put(path, doc);
        cache.put(new RepoSnapshot(service, "rubo92i/mimobike_" + service, "master", "sha-1",
                Instant.now(), docs));
    }

    @Test
    void listServicesShowsLoadedAndNotLoaded() {
        KnowledgeTools.ListServicesResponse response = tools.listServices();

        assertThat(response.services()).hasSize(2);
        KnowledgeTools.ServiceInfo ipay = response.services().get(0);
        assertThat(ipay.service()).isEqualTo("ipay");
        assertThat(ipay.status()).isEqualTo("loaded");
        assertThat(ipay.commitSha()).isEqualTo("sha-1");
        assertThat(ipay.documents()).extracting(KnowledgeTools.DocInfo::path)
                .containsExactly("docs/mobile-api.md");

        KnowledgeTools.ServiceInfo sharing = response.services().get(1);
        assertThat(sharing.status()).isEqualTo("not loaded");
        assertThat(sharing.repository()).isEqualTo("rubo92i/mimobike_sharing");
    }

    @Test
    void searchDocsReturnsExcerptsWithSourceMetadata() {
        KnowledgeTools.SearchResponse response = tools.searchDocs("wallet topup", "ipay", 5);

        assertThat(response.error()).isNull();
        assertThat(response.results()).isNotEmpty();
        SearchService.Hit hit = response.results().get(0);
        assertThat(hit.repository()).isEqualTo("rubo92i/mimobike_ipay");
        assertThat(hit.commitSha()).isEqualTo("sha-1");
        assertThat(hit.url()).startsWith("https://github.com/");
        assertThat(hit.excerpt().length()).isLessThanOrEqualTo(510);
    }

    @Test
    void searchValidatesInput() {
        assertThat(tools.searchDocs("a", null, 5).error()).contains("at least 2");
        assertThat(tools.searchDocs("x".repeat(201), null, 5).error()).contains("at most 200");
        assertThat(tools.searchDocs("wallet", "nope", 5).error()).contains("Unknown service");
    }

    @Test
    void readDocReturnsFullSmallDocument() {
        KnowledgeTools.ReadDocResponse response =
                tools.readDoc("ipay", "docs/mobile-api.md", null);

        assertThat(response.error()).isNull();
        assertThat(response.content()).contains("POST /api/v1/wallet/topup");
        assertThat(response.source().commitSha()).isEqualTo("sha-1");
        assertThat(response.source().url()).contains("docs/mobile-api.md");
    }

    @Test
    void readDocByHeadingReturnsOnlyThatSection() {
        KnowledgeTools.ReadDocResponse response =
                tools.readDoc("ipay", "docs/mobile-api.md", "POST /api/v1/wallet/topup");

        assertThat(response.error()).isNull();
        assertThat(response.heading()).isEqualTo("POST /api/v1/wallet/topup");
        assertThat(response.content()).contains("WalletTopUpRequest");
        assertThat(response.content()).doesNotContain("Wallet basics");
    }

    @Test
    void oversizedDocumentReturnsTableOfContentsAndRequiresHeading() {
        StringBuilder big = new StringBuilder("# Big doc\n");
        for (int i = 0; i < 40; i++) {
            big.append("\n## Section ").append(i).append('\n')
                    .append("text ".repeat(100));
        }
        putDoc("ipay", "docs/big.md", big.toString());

        KnowledgeTools.ReadDocResponse response = tools.readDoc("ipay", "docs/big.md", null);

        assertThat(response.content()).isNull();
        assertThat(response.tableOfContents()).hasSizeGreaterThan(30);
        assertThat(response.hint()).contains("heading");

        KnowledgeTools.ReadDocResponse section =
                tools.readDoc("ipay", "docs/big.md", "Section 7");
        assertThat(section.error()).isNull();
        assertThat(section.content()).isNotBlank();
        assertThat(section.content().length()).isLessThanOrEqualTo(8100);
    }

    @Test
    void readDocRejectsUnknownServiceAndPath() {
        assertThat(tools.readDoc("nope", "docs/x.md", null).error())
                .contains("Unknown service");
        assertThat(tools.readDoc("nope", "docs/x.md", null).availableServices())
                .containsExactly("ipay", "sharing");
        assertThat(tools.readDoc("sharing", "docs/x.md", null).error())
                .contains("not loaded");

        KnowledgeTools.ReadDocResponse badPath = tools.readDoc("ipay", "docs/nope.md", null);
        assertThat(badPath.error()).contains("Unknown or not allowlisted");
        assertThat(badPath.availablePaths()).containsExactly("docs/mobile-api.md");
    }

    @Test
    void traversalAndSecretPathsAreNotExposed() {
        // These paths are not cache keys, so they can never resolve to content.
        assertThat(tools.readDoc("ipay", "../.env", null).error()).isNotNull();
        assertThat(tools.readDoc("ipay", "docs/../../.env", null).error()).isNotNull();
        assertThat(tools.readDoc("ipay", "/etc/passwd", null).error()).isNotNull();
        assertThat(tools.readDoc("ipay", "src/main/resources/application.yml", null).error())
                .isNotNull();
        assertThat(tools.fetch("ipay:../.env").error()).isNotNull();
    }

    @Test
    void searchAliasBehavesLikeSearchDocs() {
        KnowledgeTools.SearchResponse alias = tools.search("WALLET_LIMIT_EXCEEDED");
        assertThat(alias.error()).isNull();
        assertThat(alias.results()).isNotEmpty();
    }

    @Test
    void fetchAliasResolvesIdsFromSearchResults() {
        KnowledgeTools.SearchResponse search = tools.searchDocs("wallet topup", null, 5);
        String id = search.results().get(0).id();

        KnowledgeTools.ReadDocResponse fetched = tools.fetch(id);
        assertThat(fetched.error()).isNull();
        assertThat(fetched.content()).isNotBlank();
        assertThat(fetched.source().service()).isEqualTo("ipay");

        assertThat(tools.fetch("garbage").error()).contains("Invalid id");
        assertThat(tools.fetch(null).error()).contains("Invalid id");
    }
}
