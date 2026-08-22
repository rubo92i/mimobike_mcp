package com.mimobike.knowledge.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.config.KnowledgeProperties.RepositoryConfig;
import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.model.DocSection;
import com.mimobike.knowledge.model.RepoSnapshot;
import com.mimobike.knowledge.search.SearchService;
import com.mimobike.knowledge.security.Audit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The complete MCP tool surface: read-only, bounded responses, sourced
 * exclusively from the allowlisted in-memory cache. {@code search}/{@code fetch}
 * are thin compatibility aliases over {@code search_docs}/{@code read_doc}.
 */
public class KnowledgeTools {

    private final DocumentCache cache;
    private final SearchService searchService;
    private final KnowledgeProperties properties;

    public KnowledgeTools(DocumentCache cache, SearchService searchService,
                          KnowledgeProperties properties) {
        this.cache = cache;
        this.searchService = searchService;
        this.properties = properties;
    }

    // ---------- response shapes ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocInfo(String path, String title, String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceInfo(String service, String repository, String branch,
                              String commitSha, Instant lastLoaded, String status,
                              List<DocInfo> documents) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListServicesResponse(String cacheState, List<ServiceInfo> services) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResponse(String error, String query, String service,
                                 Integer totalResults, List<SearchService.Hit> results,
                                 String hint) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceInfo(String service, String repository, String branch, String path,
                             String commitSha, String url, Instant lastLoaded) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TocEntry(int level, String heading, String anchor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadDocResponse(String error, String title, String heading, String content,
                                  Boolean truncated, List<TocEntry> tableOfContents,
                                  String hint, SourceInfo source, List<String> availableServices,
                                  List<String> availablePaths) {
    }

    // ---------- tools ----------

    @Tool(name = "list_services",
            description = "List every documented Mimo/EVUP backend service with its repository, "
                    + "branch, current commit SHA and the available documentation paths. "
                    + "Call this first to discover valid 'service' and 'path' arguments.")
    public ListServicesResponse listServices() {
        Audit.tool("list_services", null, null, null);
        List<ServiceInfo> services = new ArrayList<>();
        for (RepositoryConfig config : properties.repositories()) {
            Optional<RepoSnapshot> snapshot = cache.snapshot(config.service());
            if (snapshot.isPresent()) {
                RepoSnapshot s = snapshot.get();
                List<DocInfo> docs = s.documents().values().stream()
                        .map(d -> new DocInfo(d.path(), d.title(), d.type()))
                        .toList();
                services.add(new ServiceInfo(s.service(), s.repository(), s.branch(),
                        s.commitSha(), s.loadedAt(), "loaded", docs));
            } else {
                services.add(new ServiceInfo(config.service(), config.repository(),
                        config.branch(), null, null, "not loaded", List.of()));
            }
        }
        return new ListServicesResponse(cacheState(), services);
    }

    @Tool(name = "search_docs",
            description = "Search Mimo/EVUP internal documentation (titles, headings, paths, "
                    + "content and OpenAPI operations). Exact endpoint paths (e.g. "
                    + "'/api/v1/wallet'), DTO names, enum values and error codes rank highest. "
                    + "Returns short excerpts with repository, path, commit SHA and source URL — "
                    + "use read_doc/fetch for the full section.")
    public SearchResponse searchDocs(
            @ToolParam(description = "Search text: an endpoint path, DTO name, error code or "
                    + "free text. 2-200 characters.") String query,
            @ToolParam(description = "Optional service id filter, e.g. 'ipay' "
                    + "(see list_services).", required = false) String service,
            @ToolParam(description = "Maximum results: default 5, maximum 10.",
                    required = false) Integer limit) {
        return doSearch("search_docs", query, service, limit);
    }

    @Tool(name = "search",
            description = "Compatibility alias of search_docs: full-text search over Mimo/EVUP "
                    + "internal documentation. Returns bounded excerpts with source metadata.")
    public SearchResponse search(
            @ToolParam(description = "Search text: an endpoint path, DTO name, error code or "
                    + "free text. 2-200 characters.") String query) {
        return doSearch("search", query, null, null);
    }

    @Tool(name = "read_doc",
            description = "Read one allowlisted documentation file (or one section of it) with "
                    + "source metadata. If the document is large, its table of contents is "
                    + "returned instead and a 'heading' argument is required.")
    public ReadDocResponse readDoc(
            @ToolParam(description = "Service id, e.g. 'ipay' (see list_services).") String service,
            @ToolParam(description = "Repository-relative document path, e.g. "
                    + "'docs/mobile-api.md'.") String path,
            @ToolParam(description = "Optional: return only the section with this heading "
                    + "(heading text or anchor).", required = false) String heading) {
        return doRead("read_doc", service, path, heading);
    }

    @Tool(name = "fetch",
            description = "Compatibility alias of read_doc. Fetch a document (or section) by the "
                    + "id returned from search results: 'service:path' or 'service:path#anchor'.")
    public ReadDocResponse fetch(
            @ToolParam(description = "Document id from search results, e.g. "
                    + "'ipay:docs/mobile-api.md#post-apiv1wallettopup'.") String id) {
        if (id == null || id.isBlank() || !id.contains(":")) {
            return error("Invalid id. Expected 'service:path' or 'service:path#anchor'.", null);
        }
        String s = id.strip();
        int colon = s.indexOf(':');
        String service = s.substring(0, colon);
        String rest = s.substring(colon + 1);
        String heading = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            heading = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        return doRead("fetch", service, rest, heading);
    }

    // ---------- implementation ----------

    private SearchResponse doSearch(String tool, String query, String service, Integer limit) {
        String q = query == null ? "" : query.strip();
        Audit.tool(tool, service, null, "query_chars=" + q.length());
        if (q.length() < 2) {
            return new SearchResponse("Query must be at least 2 characters.", q, service,
                    null, null, null);
        }
        if (q.length() > 200) {
            return new SearchResponse("Query must be at most 200 characters.", null, service,
                    null, null, null);
        }
        if (service != null && !service.isBlank() && findConfig(service).isEmpty()) {
            return new SearchResponse("Unknown service '" + service + "'.", q, service,
                    null, null, "Valid services: " + serviceIds());
        }
        List<SearchService.Hit> hits = searchService.search(q, service, limit);
        String hint = null;
        if (hits.isEmpty()) {
            hint = cache.snapshots().isEmpty()
                    ? "Documentation cache is empty (state " + cacheState() + ")."
                    : "No matches. Try list_services to see available documents.";
        }
        return new SearchResponse(null, q, service == null || service.isBlank() ? null : service,
                hits.size(), hits, hint);
    }

    private ReadDocResponse doRead(String tool, String service, String path, String heading) {
        Audit.tool(tool, service, path, heading == null ? null : "heading=" + sanitize(heading));
        if (service == null || service.isBlank()) {
            return error("Missing 'service'.", serviceIds());
        }
        Optional<RepoSnapshot> snapshot = cache.snapshot(service.strip());
        if (snapshot.isEmpty()) {
            if (findConfig(service).isPresent()) {
                return error("Service '" + service.strip() + "' is configured but not loaded yet"
                        + " (cache state " + cacheState() + "). Try again shortly.", null);
            }
            return error("Unknown service '" + service.strip() + "'.", serviceIds());
        }
        if (path == null || path.isBlank()) {
            return errorWithPaths("Missing 'path'.", snapshot.get());
        }
        Optional<CachedDocument> docOpt = cache.document(snapshot.get().service(), path);
        if (docOpt.isEmpty()) {
            return errorWithPaths("Unknown or not allowlisted document path '"
                    + sanitize(path) + "'.", snapshot.get());
        }
        CachedDocument doc = docOpt.get();
        SourceInfo source = new SourceInfo(doc.service(), doc.repository(), doc.branch(),
                doc.path(), doc.commitSha(), doc.url(), doc.lastRefresh());
        int maxChars = properties.maxDocumentChars();

        if (heading != null && !heading.isBlank()) {
            Optional<DocSection> section = findSection(doc, heading.strip());
            if (section.isEmpty()) {
                return new ReadDocResponse("Heading '" + sanitize(heading) + "' not found.",
                        doc.title(), null, null, null, toc(doc),
                        "Pick one of the headings in tableOfContents.", source, null, null);
            }
            DocSection s = section.get();
            String content = s.content();
            boolean truncated = content.length() > maxChars;
            if (truncated) {
                content = content.substring(0, maxChars) + "\n… [truncated]";
            }
            return new ReadDocResponse(null, doc.title(), s.heading(), content,
                    truncated ? Boolean.TRUE : null, null, null, source, null, null);
        }

        if (doc.content().length() <= maxChars) {
            return new ReadDocResponse(null, doc.title(), null, doc.content(),
                    null, null, null, source, null, null);
        }
        return new ReadDocResponse(null, doc.title(), null, null, null, toc(doc),
                "Document is " + doc.content().length() + " characters — too large to return "
                        + "whole. Call again with a 'heading' from tableOfContents.",
                source, null, null);
    }

    private Optional<DocSection> findSection(CachedDocument doc, String heading) {
        String wanted = heading.toLowerCase(Locale.ROOT);
        Optional<DocSection> exact = doc.sections().stream()
                .filter(s -> s.heading() != null)
                .filter(s -> s.heading().toLowerCase(Locale.ROOT).equals(wanted)
                        || (s.anchor() != null && s.anchor().equals(wanted)))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return doc.sections().stream()
                .filter(s -> s.heading() != null)
                .filter(s -> s.heading().toLowerCase(Locale.ROOT).contains(wanted))
                .findFirst();
    }

    private List<TocEntry> toc(CachedDocument doc) {
        return doc.headings().stream()
                .map(h -> new TocEntry(h.level(), h.text(), h.anchor()))
                .toList();
    }

    private ReadDocResponse error(String message, List<String> availableServices) {
        return new ReadDocResponse(message, null, null, null, null, null, null, null,
                availableServices, null);
    }

    private ReadDocResponse errorWithPaths(String message, RepoSnapshot snapshot) {
        List<String> paths = snapshot.documents().keySet().stream().limit(50).toList();
        return new ReadDocResponse(message, null, null, null, null, null,
                "Use one of availablePaths.", null, null, paths);
    }

    private Optional<RepositoryConfig> findConfig(String service) {
        String s = service.strip();
        return properties.repositories().stream()
                .filter(r -> r.service().equalsIgnoreCase(s))
                .findFirst();
    }

    private List<String> serviceIds() {
        return properties.repositories().stream().map(RepositoryConfig::service).toList();
    }

    private String cacheState() {
        return cache.state(properties.repositories().size()).name();
    }

    private static String sanitize(String value) {
        String v = value.strip();
        return v.length() > 120 ? v.substring(0, 120) : v;
    }
}
