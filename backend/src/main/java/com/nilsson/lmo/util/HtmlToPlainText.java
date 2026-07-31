package com.nilsson.lmo.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <p>The {@code HtmlToPlainText} class reduces the HTML model descriptions served by Civitai to
 * the plain text that Stable Diffusion front-ends expect.</p>
 *
 * <p>AUTOMATIC1111 and its forks escape card descriptions by default
 * ({@code extra_networks_card_description_is_html} is off), so stored markup would surface as
 * literal {@code <p>} tags on every card, and the metadata editor's Description field is a plain
 * text area regardless. This converter keeps the prose and discards everything else.</p>
 *
 * <p>Implementation Details:
 * <ul>
 *   <li><b>Structural Fidelity:</b> Block-level and break tags become line breaks; inline tags
 *   vanish without disturbing the surrounding words.</li>
 *   <li><b>Quote Awareness:</b> Tags are scanned rather than pattern-matched, so a {@code >}
 *   inside an attribute value cannot leak markup into the output.</li>
 *   <li><b>Entity Decoding:</b> Named and numeric character references are resolved in a single
 *   pass, so encoded markup such as {@code &amp;lt;} cannot be double-decoded into a tag.</li>
 *   <li><b>Noise Removal:</b> {@code script} and {@code style} bodies are dropped wholesale, as
 *   are embedded images and frames.</li>
 * </ul>
 * </p>
 *
 * <p>This is a deliberately small, dependency-free converter for display text — not a general
 * purpose HTML parser.</p>
 */
public final class HtmlToPlainText {

    /** Tags whose boundaries represent a line break in the rendered document. */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "br", "div", "li", "ul", "ol", "tr", "table", "blockquote", "pre", "hr", "section",
            "article", "header", "footer", "h1", "h2", "h3", "h4", "h5", "h6");

    /** Tags whose entire body is discarded rather than rendered. */
    private static final Set<String> OPAQUE_TAGS = Set.of("script", "style");

    private static final List<String> ENTITY_NAMES = List.of("amp", "lt", "gt", "quot", "apos", "nbsp");
    private static final List<String> ENTITY_VALUES = List.of("&", "<", ">", "\"", "'", " ");

    private static final int MAX_ENTITY_LENGTH = 10;

    private HtmlToPlainText() {
    }

    /**
     * Converts an HTML fragment to plain text.
     *
     * @param html
     *         the markup to convert; may be {@code null}
     *
     * @return the extracted text, never {@code null} and never surrounded by whitespace
     */
    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return normalizeWhitespace(decodeEntities(stripTags(html)));
    }

    /**
     * Removes markup, replacing block-level boundaries with newlines. Attribute values are
     * skipped with their quoting respected, so markup characters inside them are not emitted.
     */
    private static String stripTags(String html) {
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;

        while (i < html.length()) {
            char c = html.charAt(i);

            if (c != '<' || !startsTag(html, i)) {
                out.append(c);
                i++;
                continue;
            }

            int nameStart = html.charAt(i + 1) == '/' ? i + 2 : i + 1;
            String tagName = readTagName(html, nameStart);
            int tagEnd = findTagEnd(html, i);

            if (OPAQUE_TAGS.contains(tagName)) {
                i = skipOpaqueBody(html, tagEnd, tagName);
                continue;
            }

            if (BLOCK_TAGS.contains(tagName)) {
                out.append('\n');
            }
            i = tagEnd;
        }
        return out.toString();
    }

    /** A {@code <} only opens a tag when followed by a name or a closing slash. */
    private static boolean startsTag(String html, int index) {
        int next = index + 1;
        if (next >= html.length()) {
            return false;
        }
        char c = html.charAt(next);
        return Character.isLetter(c) || (c == '/' && next + 1 < html.length() && Character.isLetter(html.charAt(next + 1)));
    }

    private static String readTagName(String html, int start) {
        int end = start;
        while (end < html.length() && Character.isLetterOrDigit(html.charAt(end))) {
            end++;
        }
        return html.substring(start, end).toLowerCase(Locale.ROOT);
    }

    /** @return the index just past the tag's closing {@code >}, or the end of input. */
    private static int findTagEnd(String html, int tagStart) {
        char quote = 0;

        for (int i = tagStart + 1; i < html.length(); i++) {
            char c = html.charAt(i);

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return html.length();
    }

    /** @return the index just past the matching closing tag, or the end of input. */
    private static int skipOpaqueBody(String html, int bodyStart, String tagName) {
        String closingTag = "</" + tagName;
        int close = indexOfIgnoreCase(html, closingTag, bodyStart);
        return close < 0 ? html.length() : findTagEnd(html, close);
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    /**
     * Resolves character references in a single left-to-right pass. Decoding in one pass keeps
     * escaped markup escaped: {@code &amp;lt;} yields the literal text {@code &lt;}, not a tag.
     */
    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length());
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }

            int semicolon = text.indexOf(';', i + 1);
            if (semicolon < 0 || semicolon - i > MAX_ENTITY_LENGTH) {
                out.append(c);
                i++;
                continue;
            }

            String body = text.substring(i + 1, semicolon);
            String decoded = decodeEntityBody(body);

            if (decoded == null) {
                out.append(c);
                i++;
            } else {
                out.append(decoded);
                i = semicolon + 1;
            }
        }
        return out.toString();
    }

    /** @return the replacement text, or {@code null} when the body is not a known reference. */
    private static String decodeEntityBody(String body) {
        if (body.isEmpty()) {
            return null;
        }

        int named = ENTITY_NAMES.indexOf(body.toLowerCase(Locale.ROOT));
        if (named >= 0) {
            return ENTITY_VALUES.get(named);
        }

        if (body.charAt(0) != '#') {
            return null;
        }

        try {
            boolean hex = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
            int codePoint = hex
                    ? Integer.parseInt(body.substring(2), 16)
                    : Integer.parseInt(body.substring(1));
            return Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Collapses horizontal whitespace, trims each line, and drops blank lines so that the sparse
     * markup Civitai authors produce does not become a wall of empty space.
     */
    private static String normalizeWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());

        for (String line : text.split("\n")) {
            String collapsed = line.replaceAll("[ \\t\\x0B\\f\\r\\u00A0]+", " ").trim();
            if (collapsed.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(collapsed);
        }
        return out.toString();
    }
}
