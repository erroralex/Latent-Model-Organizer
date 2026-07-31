package com.nilsson.lmo.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>The {@code HtmlToPlainTextTest} suite validates the reduction of Civitai's HTML model
 * descriptions to the plain text that Stable Diffusion front-ends display.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Block Structure:</b> Asserts paragraph, break, and list tags become line breaks.</li>
 *   <li><b>Entity Decoding:</b> Verifies named and numeric character references are resolved.</li>
 *   <li><b>Noise Removal:</b> Ensures scripts, styles, and embedded media leave no residue.</li>
 *   <li><b>Whitespace Hygiene:</b> Confirms blank-line runs collapse and output is trimmed.</li>
 * </ul>
 * </p>
 */
class HtmlToPlainTextTest {

    @Test
    void shouldReturnPlainTextUnchanged() {
        assertEquals("just a plain sentence", HtmlToPlainText.convert("just a plain sentence"));
    }

    @Test
    void shouldStripInlineTagsButKeepText() {
        assertEquals("NoobAI-XL EPSILON.",
                HtmlToPlainText.convert("<strong><em>NoobAI-XL EPSILON.</em></strong>"));
    }

    @Test
    void shouldTurnParagraphsIntoLineBreaks() {
        String html = "<p>First line.</p><p>Second line.</p>";

        assertEquals("First line.\nSecond line.", HtmlToPlainText.convert(html));
    }

    @Test
    void shouldTurnBreakTagsIntoLineBreaks() {
        assertEquals("one\ntwo\nthree",
                HtmlToPlainText.convert("one<br>two<br/>three"));
    }

    @Test
    void shouldTurnListItemsIntoLines() {
        String html = "<ul><li>weight 0.8</li><li>clip skip 2</li></ul>";

        assertEquals("weight 0.8\nclip skip 2", HtmlToPlainText.convert(html));
    }

    @Test
    void shouldDecodeNamedEntities() {
        assertEquals("Tom & Jerry <the \"best\">",
                HtmlToPlainText.convert("Tom &amp; Jerry &lt;the &quot;best&quot;&gt;"));
    }

    @Test
    void shouldDecodeNumericEntities() {
        assertEquals("it's 100% fine",
                HtmlToPlainText.convert("it&#39;s 100&#37; fine"));
    }

    @Test
    void shouldTreatNonBreakingSpaceAsSpace() {
        assertEquals("weight 0.8", HtmlToPlainText.convert("weight&nbsp;0.8"));
    }

    @Test
    void shouldRemoveScriptAndStyleContent() {
        String html = "<style>.card{color:red}</style>Real text<script>alert('x')</script>";

        assertEquals("Real text", HtmlToPlainText.convert(html));
    }

    @Test
    void shouldCollapseRunsOfBlankLines() {
        String html = "<p>One</p><p></p><p></p><p>Two</p>";

        assertEquals("One\nTwo", HtmlToPlainText.convert(html));
    }

    @Test
    void shouldCollapseHorizontalWhitespace() {
        assertEquals("spaced out text",
                HtmlToPlainText.convert("spaced    out \t text"));
    }

    @Test
    void shouldDropImagesAndIframesEntirely() {
        String html = "<p>Preview:</p><img src=\"x.png\" alt=\"shot\"><iframe src=\"y\"></iframe>";

        assertEquals("Preview:", HtmlToPlainText.convert(html));
    }

    @Test
    void shouldHandleNullAndBlankInput() {
        assertEquals("", HtmlToPlainText.convert(null));
        assertEquals("", HtmlToPlainText.convert("   "));
        assertEquals("", HtmlToPlainText.convert("<p></p>"));
    }

    @Test
    void shouldNotLeaveResidueFromUnclosedTags() {
        assertEquals("text", HtmlToPlainText.convert("<p class=\"a\" data-x='<>'>text"));
    }
}
