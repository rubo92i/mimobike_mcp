package com.mimobike.knowledge.search;

import com.mimobike.knowledge.cache.DocumentCache;
import com.mimobike.knowledge.config.KnowledgeProperties;
import com.mimobike.knowledge.model.CachedDocument;
import com.mimobike.knowledge.model.DocSection;
import com.mimobike.knowledge.model.RepoSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic lexical search over titles, headings, paths and content.
 * Exact endpoint paths, DTO names, enum constants and error codes outrank
 * free-text matches. Results are always bounded (max 10) and excerpt-sized.
 */
public class SearchService {

    public static final int MAX_LIMIT = 10;
    public static final int DEFAULT_LIMIT = 5;

    private static final Pattern URL_PATH = Pattern.compile("(/[A-Za-z0-9_\\-{}][A-Za-z0-9_\\-{}/.]*)");
    private static final Pattern HTTP_METHOD =
            Pattern.compile("(?i)\\b(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\b");
    private static final Pattern IDENTIFIER =
            Pattern.compile("([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+|[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)*)");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]{2,}");

    private final DocumentCache cache;
    private final KnowledgeProperties properties;

    public SearchService(DocumentCache cache, KnowledgeProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    public record Hit(String service, String repository, String path, String title,
                      String heading, String excerpt, String commitSha, String url, String id) {
    }

    public List<Hit> search(String query, String serviceFilter, Integer requestedLimit) {
        int limit = clampLimit(requestedLimit);
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return List.of();
        }

        String qLower = q.toLowerCase(Locale.ROOT);
        List<String> urlPaths = allMatches(URL_PATH, q);
        List<String> methods = allMatches(HTTP_METHOD, q).stream()
                .map(m -> m.toUpperCase(Locale.ROOT)).toList();
        List<String> identifiers = allMatches(IDENTIFIER, q);
        List<String> tokens = allMatches(TOKEN, qLower);

        record Scored(double score, Hit hit) {
        }
        List<Scored> scored = new ArrayList<>();

        for (RepoSnapshot snapshot : cache.snapshots()) {
            if (serviceFilter != null && !serviceFilter.isBlank()
                    && !snapshot.service().equalsIgnoreCase(serviceFilter.strip())) {
                continue;
            }
            for (CachedDocument doc : snapshot.documents().values()) {
                DocSection best = null;
                double bestScore = 0;
                for (DocSection section : doc.sections()) {
                    double s = scoreSection(doc, section, qLower, urlPaths, methods, identifiers, tokens);
                    if (s > bestScore) {
                        bestScore = s;
                        best = section;
                    }
                }
                if (best != null && bestScore > 0) {
                    scored.add(new Scored(bestScore, toHit(doc, best, qLower, urlPaths, tokens)));
                }
            }
        }

        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .map(Scored::hit)
                .toList();
    }

    public static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    private double scoreSection(CachedDocument doc, DocSection section, String qLower,
                                List<String> urlPaths, List<String> methods,
                                List<String> identifiers, List<String> tokens) {
        String headingLower = section.heading() == null ? ""
                : section.heading().toLowerCase(Locale.ROOT);
        String contentLower = section.content().toLowerCase(Locale.ROOT);
        String titleLower = doc.title() == null ? "" : doc.title().toLowerCase(Locale.ROOT);
        String pathLower = doc.path().toLowerCase(Locale.ROOT);

        double score = 0;

        // Exact endpoint-path matches rank first.
        for (String urlPath : urlPaths) {
            String p = urlPath.toLowerCase(Locale.ROOT);
            if (headingLower.contains(p)) {
                score += 100;
                for (String method : methods) {
                    if (headingLower.startsWith(method.toLowerCase(Locale.ROOT) + " ")) {
                        score += 20;
                    }
                }
            } else if (contentLower.contains(p)) {
                score += 70;
            }
        }

        // Exact (case-sensitive) DTO / enum / error-code identifiers next.
        int identifierHits = 0;
        for (String identifier : identifiers) {
            if (section.content().contains(identifier)
                    || (section.heading() != null && section.heading().contains(identifier))) {
                identifierHits++;
            }
        }
        score += 60.0 * Math.min(identifierHits, 2);

        // Whole-phrase match.
        if (qLower.length() >= 4) {
            if (headingLower.contains(qLower)) {
                score += 60;
            } else if (contentLower.contains(qLower)) {
                score += 40;
            }
        }

        // Token scoring across title, heading, path and content.
        int present = 0;
        for (String token : tokens) {
            boolean found = false;
            if (titleLower.contains(token)) {
                score += 15;
                found = true;
            }
            if (headingLower.contains(token)) {
                score += 20;
                found = true;
            }
            if (pathLower.contains(token)) {
                score += 10;
                found = true;
            }
            int occurrences = countOccurrences(contentLower, token);
            if (occurrences > 0) {
                score += 2.0 * Math.min(occurrences, 5);
                found = true;
            }
            if (found) {
                present++;
            }
        }
        if (!tokens.isEmpty() && present == tokens.size()) {
            score += 25;
        }
        return score;
    }

    private Hit toHit(CachedDocument doc, DocSection section, String qLower,
                      List<String> urlPaths, List<String> tokens) {
        String anchor = section.anchor();
        String id = doc.service() + ":" + doc.path() + (anchor == null ? "" : "#" + anchor);
        String url = doc.url() + (anchor == null ? "" : "#" + anchor);
        return new Hit(doc.service(), doc.repository(), doc.path(), doc.title(),
                section.heading(), excerpt(section.content(), qLower, urlPaths, tokens),
                doc.commitSha(), url, id);
    }

    private String excerpt(String content, String qLower, List<String> urlPaths, List<String> tokens) {
        int max = properties.maxExcerptChars();
        if (content.length() <= max) {
            return content;
        }
        String contentLower = content.toLowerCase(Locale.ROOT);
        int position = -1;
        if (!urlPaths.isEmpty()) {
            position = contentLower.indexOf(urlPaths.get(0).toLowerCase(Locale.ROOT));
        }
        if (position < 0) {
            position = contentLower.indexOf(qLower);
        }
        if (position < 0) {
            for (String token : tokens) {
                position = contentLower.indexOf(token);
                if (position >= 0) {
                    break;
                }
            }
        }
        if (position < 0) {
            position = 0;
        }
        int start = Math.max(0, position - max / 3);
        int end = Math.min(content.length(), start + max);
        if (start > 0) {
            int space = content.indexOf(' ', start);
            if (space > 0 && space < end) {
                start = space + 1;
            }
        }
        String cut = content.substring(start, end).strip();
        return (start > 0 ? "…" : "") + cut + (end < content.length() ? "…" : "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static List<String> allMatches(Pattern pattern, String input) {
        List<String> matches = new ArrayList<>();
        Matcher m = pattern.matcher(input);
        while (m.find()) {
            matches.add(m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group());
        }
        return matches;
    }
}
