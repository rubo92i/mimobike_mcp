package com.mimobike.knowledge.parse;

import com.mimobike.knowledge.model.DocSection;
import com.mimobike.knowledge.model.Heading;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Splits Markdown by ATX headings (# .. ######), never by token counts.
 * Headings inside fenced code blocks are ignored. YAML front matter is parsed
 * into metadata and stripped from the body.
 */
public final class MarkdownParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownParser.class);
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private MarkdownParser() {
    }

    public record Parsed(
            String title,
            Map<String, Object> frontMatter,
            String body,
            List<Heading> headings,
            List<DocSection> sections) {
    }

    public static Parsed parse(String raw, String fallbackTitle) {
        String content = raw == null ? "" : raw.replace("\r\n", "\n");

        Map<String, Object> frontMatter = Map.of();
        String[] lines = content.split("\n", -1);
        int bodyStart = 0;

        if (lines.length > 0 && lines[0].strip().equals("---")) {
            for (int i = 1; i < lines.length; i++) {
                String stripped = lines[i].strip();
                if (stripped.equals("---") || stripped.equals("...")) {
                    frontMatter = parseFrontMatter(String.join("\n",
                            List.of(lines).subList(1, i)));
                    bodyStart = i + 1;
                    break;
                }
            }
        }

        List<Heading> headings = new ArrayList<>();
        List<DocSection> sections = new ArrayList<>();
        Map<String, Integer> anchorCounts = new HashMap<>();

        String currentHeading = null;
        int currentLevel = 0;
        String currentAnchor = null;
        StringBuilder currentContent = new StringBuilder();
        boolean inFence = false;

        for (int i = bodyStart; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                inFence = !inFence;
            }
            Matcher m = inFence ? null : HEADING.matcher(line);
            if (m != null && m.matches()) {
                addSection(sections, currentHeading, currentLevel, currentAnchor, currentContent);
                currentContent = new StringBuilder();
                currentHeading = m.group(2).strip();
                currentLevel = m.group(1).length();
                currentAnchor = uniqueAnchor(slug(currentHeading), anchorCounts);
                headings.add(new Heading(currentLevel, currentHeading, currentAnchor));
            } else {
                currentContent.append(line).append('\n');
            }
        }
        addSection(sections, currentHeading, currentLevel, currentAnchor, currentContent);

        String body = bodyStart == 0 ? content
                : String.join("\n", List.of(lines).subList(bodyStart, lines.length));

        String title = titleFrom(frontMatter, headings, fallbackTitle);
        return new Parsed(title, frontMatter, body, List.copyOf(headings), List.copyOf(sections));
    }

    private static void addSection(List<DocSection> sections, String heading, int level,
                                   String anchor, StringBuilder content) {
        String text = content.toString().strip();
        if (heading == null && text.isEmpty()) {
            return;
        }
        sections.add(new DocSection(heading, level, anchor, text));
    }

    private static String titleFrom(Map<String, Object> frontMatter,
                                    List<Heading> headings, String fallbackTitle) {
        Object fm = frontMatter.get("title");
        if (fm instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        for (Heading h : headings) {
            if (h.level() == 1) {
                return h.text();
            }
        }
        if (!headings.isEmpty()) {
            return headings.get(0).text();
        }
        return fallbackTitle;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFrontMatter(String yaml) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            if (loaded instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
        } catch (RuntimeException e) {
            log.debug("Unparseable front matter ignored: {}", e.getMessage());
        }
        return Map.of();
    }

    static String slug(String heading) {
        String s = heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s_-]", "")
                .strip()
                .replaceAll("\\s+", "-");
        return s.isEmpty() ? "section" : s;
    }

    private static String uniqueAnchor(String base, Map<String, Integer> counts) {
        int seen = counts.merge(base, 1, Integer::sum);
        return seen == 1 ? base : base + "-" + (seen - 1);
    }
}
