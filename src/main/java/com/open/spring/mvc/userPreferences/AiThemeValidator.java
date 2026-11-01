package com.open.spring.mvc.userPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns an AI's theme answer into a theme the site can safely apply. Nothing from the AI is
 * trusted: bad colors fall back to the site default, unknown fonts become Inter, decor keeps
 * only emoji, and text or accent colors that are hard to read on the background are fixed.
 */
public final class AiThemeValidator {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    static final String DEFAULT_BG = "#121212";
    static final String DEFAULT_TEXT = "#F0F0F0";
    static final String DEFAULT_ACCENT = "#4CAFEF";

    /** Font names the AI may pick, mapped to the same CSS values as the dashboard's font menu. */
    static final Map<String, String> FONTS = Map.of(
        "inter", "Inter, system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial",
        "roboto", "Roboto, system-ui, -apple-system, 'Segoe UI', 'Helvetica Neue', Arial",
        "open sans", "'Open Sans', Arial, sans-serif",
        "lato", "Lato, Arial, sans-serif",
        "montserrat", "Montserrat, Arial, sans-serif",
        "georgia", "Georgia, 'Times New Roman', Times, serif",
        "source code pro", "'Source Code Pro', monospace");

    /** Button styles the AI may pick, mapped to the values the Preferences form saves. */
    static final Map<String, String> BUTTON_STYLES = Map.of(
        "default", "default", "rounded", "rounded-all", "square", "square", "pill", "pill");

    /** The AI may change the text size only a little, so a theme can't make a page hard to read. */
    static final int MIN_SIZE = 13, MAX_SIZE = 16, DEFAULT_SIZE = 14;

    private AiThemeValidator() {}

    /** Returns {name, description, background, text, accent, selection, font, decor, size, buttonStyle}. */
    public static Map<String, Object> clean(JsonNode ai) {
        String bg = hex(ai.path("background").asText(), DEFAULT_BG);
        String text = hex(ai.path("text").asText(), DEFAULT_TEXT);
        if (contrast(text, bg) < 4.5) text = readableOn(bg);
        String accent = hex(ai.path("accent").asText(), DEFAULT_ACCENT);
        accent = standOut(accent, bg);

        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("name", plain(ai.path("name").asText(), 30, "AI Theme"));
        theme.put("description", plain(ai.path("description").asText(), 160, ""));
        theme.put("background", bg);
        theme.put("text", text);
        theme.put("accent", accent);
        theme.put("selection", hex(ai.path("selection").asText(), accent));
        theme.put("font", FONTS.getOrDefault(ai.path("font").asText().trim().toLowerCase(), FONTS.get("inter")));
        theme.put("decor", decor(ai.path("decor")));
        JsonNode size = ai.path("size");
        theme.put("size", size.isNumber() ? Math.max(MIN_SIZE, Math.min(MAX_SIZE, size.asInt())) : DEFAULT_SIZE);
        theme.put("buttonStyle", BUTTON_STYLES.getOrDefault(ai.path("buttonStyle").asText().trim().toLowerCase(), "default"));
        return theme;
    }

    static String hex(String value, String fallback) {
        return value != null && HEX.matcher(value.trim()).matches() ? value.trim().toLowerCase() : fallback;
    }

    /** Text that fails 4.5:1 becomes black or white, whichever reads better (one always passes). */
    static String readableOn(String bg) {
        return contrast("#000000", bg) >= contrast("#ffffff", bg) ? "#000000" : "#ffffff";
    }

    /** Links and filled buttons need 3:1 against the page; mix the accent toward black or white until it gets there. */
    static String standOut(String accent, String bg) {
        String toward = readableOn(bg);
        for (int step = 0; step <= 10 && contrast(accent, bg) < 3; step++) accent = mix(accent, toward, 0.15);
        return contrast(accent, bg) < 3 ? toward : accent;
    }

    static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int[] rgb = rgb(hex);
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = rgb[i] / 255.0;
            c[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static String mix(String from, String to, double amount) {
        int[] a = rgb(from), b = rgb(to);
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) out[i] = (int) Math.round(a[i] + (b[i] - a[i]) * amount);
        return String.format("#%02x%02x%02x", out[0], out[1], out[2]);
    }

    private static int[] rgb(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new int[] { v >> 16 & 0xff, v >> 8 & 0xff, v & 0xff };
    }

    /** Single-line text without control characters, capped at max characters. */
    static String plain(String value, int max, String fallback) {
        String s = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return fallback;
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    /** Up to 3 distinct emoji. Entries with letters, digits or markup are dropped. */
    static List<String> decor(JsonNode node) {
        Set<String> kept = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String s = item.asText().strip();
                if (isEmoji(s)) kept.add(s);
                if (kept.size() == 3) break;
            }
        }
        return new ArrayList<>(kept);
    }

    static boolean isEmoji(String s) {
        if (s.isEmpty() || s.length() > 16) return false;
        boolean symbol = false;
        for (int cp : s.codePoints().toArray()) {
            int type = Character.getType(cp);
            if (type == Character.OTHER_SYMBOL) symbol = true;
            else if (type != Character.MODIFIER_SYMBOL && cp != 0x200D && cp != 0xFE0F && cp != 0x20E3) return false;
        }
        return symbol;
    }
}
