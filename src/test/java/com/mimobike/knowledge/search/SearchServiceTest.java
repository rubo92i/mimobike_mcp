package com.mimobike.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.model.RepoSnapshot;
import com.mimobike.knowledge.parse.MarkdownParser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

    private DocumentCache cache;
    private SearchService search;

    @BeforeEach
    void setUp() {
        cache = new DocumentCache();
        KnowledgeProperties props = new KnowledgeProperties(null, 8000, 500, null, List.of());
        search = new SearchService(cache, props);

        addDoc("ipay", "docs/mobile-api.md", """
                # Wallet API

                ## Overview
                The wallet holds user funds.

                ### POST /api/v1/wallet/topup
                Tops up the wallet. Request body: WalletTopUpRequest with fields amount, currency.
                Errors: WALLET_LIMIT_EXCEEDED, PAYMENT_DECLINED.

                ### GET /api/v1/wallet/balance
                Returns the current balance as WalletBalanceResponse.
                """);
        addDoc("ipay", "docs/error-codes.md", """
                # Error codes

                ## Payment errors
                PAYMENT_DECLINED means the provider rejected the charge.
                WALLET_LIMIT_EXCEEDED means the configured top-up limit was passed.
                """);
        addDoc("sharing", "docs/authentication.md", """
                # Authentication

                ## Token flow
                Mobile clients send a JWT access token in the Authorization header.
                """);
    }

    private void addDoc(String service, String path, String markdown) {
        MarkdownParser.Parsed parsed = MarkdownParser.parse(markdown, path);
        CachedDocument doc = new CachedDocument(service, "rubo92i/" + service, "master", path,
                CachedDocument.TYPE_MARKDOWN, parsed.title(), parsed.frontMatter(),
                parsed.headings(), parsed.sections(), parsed.body(),
                "sha-" + service, "https://github.com/rubo92i/" + service + "/blob/sha/" + path,
                Instant.now());
        RepoSnapshot existing = cache.snapshot(service).orElse(null);
        Map<String, CachedDocument> docs = new LinkedHashMap<>();
        if (existing != null) {
            docs.putAll(existing.documents());
        }
        docs.put(path, doc);
        cache.put(new RepoSnapshot(service, "rubo92i/" + service, "master",
                "sha-" + service, Instant.now(), docs));
    }

    @Test
    void exactEndpointPathRanksFirst() {
        List<SearchService.Hit> hits = search.search("POST /api/v1/wallet/topup", null, 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).heading()).isEqualTo("POST /api/v1/wallet/topup");
        assertThat(hits.get(0).path()).isEqualTo("docs/mobile-api.md");
        assertThat(hits.get(0).commitSha()).isEqualTo("sha-ipay");
        assertThat(hits.get(0).url()).contains("github.com").contains("#post-apiv1wallettopup");
        assertThat(hits.get(0).id()).isEqualTo("ipay:docs/mobile-api.md#post-apiv1wallettopup");
    }

    @Test
    void dtoNameSearchFindsDefiningSection() {
        List<SearchService.Hit> hits = search.search("WalletTopUpRequest", null, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).heading()).isEqualTo("POST /api/v1/wallet/topup");
    }

    @Test
    void errorCodeSearchFindsBothDocs() {
        List<SearchService.Hit> hits = search.search("WALLET_LIMIT_EXCEEDED", null, 5);
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(SearchService.Hit::path)
                .containsExactlyInAnyOrder("docs/mobile-api.md", "docs/error-codes.md");
    }

    @Test
    void serviceFilterRestrictsResults() {
        assertThat(search.search("token", "sharing", 5))
                .allSatisfy(h -> assertThat(h.service()).isEqualTo("sharing"));
        assertThat(search.search("wallet", "sharing", 5)).isEmpty();
    }

    @Test
    void limitIsClampedToTen() {
        assertThat(SearchService.clampLimit(null)).isEqualTo(5);
        assertThat(SearchService.clampLimit(50)).isEqualTo(10);
        assertThat(SearchService.clampLimit(0)).isEqualTo(1);
        assertThat(SearchService.clampLimit(-3)).isEqualTo(1);
        assertThat(SearchService.clampLimit(7)).isEqualTo(7);

        for (int i = 0; i < 30; i++) {
            addDoc("ipay", "docs/gen-" + i + ".md", "# Wallet doc " + i + "\nwallet wallet");
        }
        assertThat(search.search("wallet", null, 999)).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void excerptsAreBoundedNotFullDocuments() {
        StringBuilder big = new StringBuilder("# Big\n\n## Section\n");
        big.append("filler sentence about rentals. ".repeat(200));
        big.append("The special keyword ZEBRA_CODE appears here. ");
        big.append("more filler. ".repeat(200));
        addDoc("sharing", "docs/big.md", big.toString());

        List<SearchService.Hit> hits = search.search("ZEBRA_CODE", null, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).excerpt().length()).isLessThanOrEqualTo(510);
        assertThat(hits.get(0).excerpt()).contains("ZEBRA_CODE");
    }

    @Test
    void emptyOrBlankQueryReturnsNothing() {
        assertThat(search.search("", null, 5)).isEmpty();
        assertThat(search.search("   ", null, 5)).isEmpty();
        assertThat(search.search(null, null, 5)).isEmpty();
    }
}
