package com.mimobike.knowledge.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimobike.knowledge.model.DocSection;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownParserTest {

    @Test
    void splitsByHeadingsWithPreamble() {
        String md = """
                Intro text before any heading.

                # Title

                Body of title.

                ## Section A

                Content A.

                ### Sub A1

                Content A1.
                """;
        MarkdownParser.Parsed parsed = MarkdownParser.parse(md, "file.md");

        assertThat(parsed.title()).isEqualTo("Title");
        assertThat(parsed.headings()).extracting("text")
                .containsExactly("Title", "Section A", "Sub A1");
        assertThat(parsed.headings()).extracting("level").containsExactly(1, 2, 3);

        List<DocSection> sections = parsed.sections();
        assertThat(sections).hasSize(4);
        assertThat(sections.get(0).heading()).isNull();
        assertThat(sections.get(0).content()).contains("Intro text");
        assertThat(sections.get(2).heading()).isEqualTo("Section A");
        assertThat(sections.get(2).content()).contains("Content A.");
    }

    @Test
    void parsesAndStripsFrontMatter() {
        String md = """
                ---
                service: ipay
                audience: [mobile, backend]
                title: Wallet API
                ---
                # Ignored As Title Source

                Content.
                """;
        MarkdownParser.Parsed parsed = MarkdownParser.parse(md, "file.md");

        assertThat(parsed.title()).isEqualTo("Wallet API");
        assertThat(parsed.frontMatter()).containsEntry("service", "ipay");
        assertThat(parsed.frontMatter().get("audience")).isEqualTo(List.of("mobile", "backend"));
        assertThat(parsed.body()).doesNotContain("service: ipay");
        assertThat(parsed.body()).contains("# Ignored As Title Source");
    }

    @Test
    void ignoresHeadingsInsideCodeFences() {
        String md = """
                # Real

                ```bash
                # not a heading
                echo hi
                ```

                ## Also Real
                """;
        MarkdownParser.Parsed parsed = MarkdownParser.parse(md, "file.md");
        assertThat(parsed.headings()).extracting("text").containsExactly("Real", "Also Real");
    }

    @Test
    void titleFallsBackToFileName() {
        MarkdownParser.Parsed parsed = MarkdownParser.parse("just text, no headings", "notes.md");
        assertThat(parsed.title()).isEqualTo("notes.md");
        assertThat(parsed.sections()).hasSize(1);
        assertThat(parsed.sections().get(0).heading()).isNull();
    }

    @Test
    void anchorsAreSluggedAndDeduplicated() {
        String md = """
                ## GET /api/v1/wallet
                a
                ## GET /api/v1/wallet
                b
                """;
        MarkdownParser.Parsed parsed = MarkdownParser.parse(md, "f.md");
        assertThat(parsed.headings().get(0).anchor()).isEqualTo("get-apiv1wallet");
        assertThat(parsed.headings().get(1).anchor()).isEqualTo("get-apiv1wallet-1");
    }

    @Test
    void neverSplitsByLengthOnlyByHeadings() {
        String longBody = "word ".repeat(5000);
        MarkdownParser.Parsed parsed = MarkdownParser.parse("# One\n\n" + longBody, "f.md");
        assertThat(parsed.sections()).hasSize(1);
        assertThat(parsed.sections().get(0).content().length()).isGreaterThan(20_000);
    }
}
