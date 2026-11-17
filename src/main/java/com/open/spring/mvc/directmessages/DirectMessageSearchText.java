package com.open.spring.mvc.directmessages;

import java.util.Locale;

import org.jsoup.Jsoup;

/**
 * Text helpers for searching direct messages. A message body is stored as the HTML the
 * rich-text composer produced, so search has to work on two forms of it: the stored HTML
 * (what the database can filter) and the plain text a person actually reads.
 */
final class DirectMessageSearchText {

    /** How many characters of context an excerpt keeps on each side of the match. */
    static final int EXCERPT_RADIUS = 60;

    private static final char LIKE_ESCAPE = '!';

    private DirectMessageSearchText() {
    }

    /** The text a reader sees: tags removed, entities decoded, whitespace collapsed. */
    static String plainText(String html) {
        return html == null ? "" : Jsoup.parse(html).text();
    }

    /**
     * A lower-cased LIKE pattern that finds {@code query} inside a stored HTML body.
     * The query is written the way the body stores it ({@code &} as {@code &amp;}), and
     * LIKE's own wildcards are escaped with '!' so they match literally.
     */
    static String likePattern(String query) {
        String stored = query.toLowerCase(Locale.ROOT)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        StringBuilder pattern = new StringBuilder("%");
        for (char character : stored.toCharArray()) {
            if (character == LIKE_ESCAPE || character == '%' || character == '_') {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(character);
        }
        return pattern.append('%').toString();
    }

    /** Where {@code query} first appears in {@code text}, ignoring case; -1 when it doesn't. */
    static int indexOfIgnoreCase(String text, String query) {
        int last = text.length() - query.length();
        for (int start = 0; start <= last; start++) {
            if (text.regionMatches(true, start, query, 0, query.length())) {
                return start;
            }
        }
        return -1;
    }

    /**
     * The part of {@code text} around the first match of {@code query}, with "..." on each
     * side that was cut. Empty when the text doesn't contain the query.
     */
    static String excerpt(String text, String query) {
        int match = indexOfIgnoreCase(text, query);
        if (match < 0) {
            return "";
        }
        int start = Math.max(0, match - EXCERPT_RADIUS);
        int end = Math.min(text.length(), match + query.length() + EXCERPT_RADIUS);
        return (start > 0 ? "..." : "") + text.substring(start, end).trim() + (end < text.length() ? "..." : "");
    }
}
