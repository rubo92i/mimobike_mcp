package com.mimobike.knowledge.parse;

import com.mimobike.knowledge.model.DocSection;
import com.mimobike.knowledge.model.Heading;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Extracts OpenAPI operations keyed by HTTP method + path. Each operation
 * becomes one section (heading "GET /some/path"), so heading-based reads and
 * exact endpoint search work the same way as for Markdown.
 */
public final class OpenApiParser {

    private static final Logger log = LoggerFactory.getLogger(OpenApiParser.class);
    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private OpenApiParser() {
    }

    public record Parsed(String title, String body, List<Heading> headings, List<DocSection> sections) {
    }

    /** Returns {@code null} when the file is not a parseable OpenAPI document. */
    public static Parsed parse(String raw, String fallbackTitle) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(raw);
        } catch (RuntimeException e) {
            log.warn("Skipping unparseable YAML: {}", e.getMessage());
            return null;
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            return null;
        }
        if (!root.containsKey("openapi") && !root.containsKey("swagger")) {
            return null;
        }
        if (!(root.get("paths") instanceof Map<?, ?> paths)) {
            return null;
        }

        String title = fallbackTitle;
        if (root.get("info") instanceof Map<?, ?> info
                && info.get("title") instanceof String t && !t.isBlank()) {
            title = t.strip();
        }

        List<Heading> headings = new ArrayList<>();
        List<DocSection> sections = new ArrayList<>();
        Map<String, Integer> anchorCounts = new HashMap<>();
        StringBuilder body = new StringBuilder(title).append('\n');

        for (Map.Entry<?, ?> pathEntry : paths.entrySet()) {
            String path = String.valueOf(pathEntry.getKey());
            if (!(pathEntry.getValue() instanceof Map<?, ?> operations)) {
                continue;
            }
            for (String method : METHODS) {
                if (!(operations.get(method) instanceof Map<?, ?> op)) {
                    continue;
                }
                String heading = method.toUpperCase(Locale.ROOT) + " " + path;
                String content = describeOperation(op);
                String anchor = uniqueAnchor(MarkdownParser.slug(heading), anchorCounts);
                headings.add(new Heading(3, heading, anchor));
                sections.add(new DocSection(heading, 3, anchor, content));
                body.append("\n### ").append(heading).append('\n').append(content).append('\n');
            }
        }
        if (sections.isEmpty()) {
            return null;
        }
        return new Parsed(title, body.toString(), List.copyOf(headings), List.copyOf(sections));
    }

    private static String describeOperation(Map<?, ?> op) {
        StringBuilder sb = new StringBuilder();
        appendIfString(sb, "Summary", op.get("summary"));
        appendIfString(sb, "Description", op.get("description"));
        appendIfString(sb, "OperationId", op.get("operationId"));
        if (op.get("tags") instanceof List<?> tags && !tags.isEmpty()) {
            sb.append("Tags: ").append(tags).append('\n');
        }
        if (op.get("parameters") instanceof List<?> params && !params.isEmpty()) {
            sb.append("Parameters:\n");
            for (Object p : params) {
                if (p instanceof Map<?, ?> pm) {
                    sb.append("- ").append(pm.get("name"))
                            .append(" (in ").append(pm.get("in"));
                    if (Boolean.TRUE.equals(pm.get("required"))) {
                        sb.append(", required");
                    }
                    sb.append(')');
                    if (pm.get("description") instanceof String d && !d.isBlank()) {
                        sb.append(": ").append(d.strip());
                    }
                    sb.append('\n');
                }
            }
        }
        if (op.get("responses") instanceof Map<?, ?> responses && !responses.isEmpty()) {
            sb.append("Responses:\n");
            for (Map.Entry<?, ?> r : responses.entrySet()) {
                sb.append("- ").append(r.getKey());
                if (r.getValue() instanceof Map<?, ?> rm
                        && rm.get("description") instanceof String d && !d.isBlank()) {
                    sb.append(": ").append(d.strip());
                }
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static void appendIfString(StringBuilder sb, String label, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            sb.append(label).append(": ").append(s.strip()).append('\n');
        }
    }

    private static String uniqueAnchor(String base, Map<String, Integer> counts) {
        int seen = counts.merge(base, 1, Integer::sum);
        return seen == 1 ? base : base + "-" + (seen - 1);
    }
}
