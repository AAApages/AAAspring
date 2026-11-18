package com.open.spring.mvc.aiconnections;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Night grading and house model settings. A value an admin saved on the AI queue page wins;
 * otherwise the .env default applies (AI_HOUSE_URL, AI_HOUSE_MODELS, AI_HOUSE_API_KEY,
 * AI_HOUSE_TIMEOUT_SECONDS and assignments.night-grading.*). Rows are name/value pairs in
 * ai_house_settings, so a new setting needs no migration. The API key is stored encrypted and
 * is never returned. Also answers whether the night window is open, since both the grader and
 * the night run need that without depending on each other.
 */
@Repository
public class AiHouseSettingsRepository implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(AiHouseSettingsRepository.class);
    static final String KEY_CONTEXT = "house-model";
    private static final String TABLE = "ai_house_settings";

    /**
     * Model servers offered in the settings dropdown. When the school GPU box is set up, add a
     * line here with its URL and models and it appears in the dropdown, filled in.
     */
    public enum Preset {
        NONE("None (night grading only reports, grades nothing)", "", "", ""),
        OLLAMA("Ollama", "http://localhost:11434/v1", "", ""),
        VLLM("vLLM", "http://localhost:8000/v1", "", ""),
        LLAMA_CPP("llama.cpp server", "http://localhost:8080/v1", "", ""),
        CUSTOM("Custom OpenAI-compatible URL", "", "", "");

        private final String label;
        private final String url;
        private final String primaryModel;
        private final String backupModel;

        Preset(String label, String url, String primaryModel, String backupModel) {
            this.label = label; this.url = url; this.primaryModel = primaryModel; this.backupModel = backupModel;
        }
        public String label() { return label; }
        public String url() { return url; }
        public String primaryModel() { return primaryModel; }
        public String backupModel() { return backupModel; }
        public boolean urlEditable() { return this == CUSTOM; }

        static Preset parse(String value) {
            try { return value == null || value.isBlank() ? null : valueOf(value.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    /** The effective settings; {@code sources} says per field whether it was "saved" or ".env". */
    public record HouseSettings(boolean enabled, String windowStart, String windowEnd, String zone,
            Preset preset, String baseUrl, String primaryModel, String backupModel, int timeoutSeconds,
            boolean apiKeySet, Map<String, String> sources, String updatedBy, String updatedAt) {
        public List<String> models() {
            return backupModel == null || backupModel.isBlank() || backupModel.equals(primaryModel)
                    ? List.of(primaryModel) : List.of(primaryModel, backupModel);
        }
        public boolean modelConfigured() {
            return preset != Preset.NONE && baseUrl != null && !baseUrl.isBlank()
                    && primaryModel != null && !primaryModel.isBlank();
        }
    }

    /**
     * What an admin saves. {@code apiKey} null or blank keeps the saved key; {@code clearApiKey}
     * removes it.
     */
    public record Changes(boolean enabled, String windowStart, String windowEnd, String zone, String preset,
            String baseUrl, String primaryModel, String backupModel, Integer timeoutSeconds, String apiKey,
            boolean clearApiKey) {
        @Override public String toString() { return "Changes[apiKey redacted]"; }
    }

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final AiKeyCipher cipher;
    private final Map<String, String> defaults = new HashMap<>();
    private final String defaultApiKey;
    private Clock clock = Clock.systemUTC();
    private volatile boolean available;
    private volatile Stored cache;

    private record Stored(Map<String, String> values, String updatedBy, String updatedAt) {
    }

    public AiHouseSettingsRepository(JdbcTemplate jdbc, DataSource dataSource, AiKeyCipher cipher,
            @Value("${assignments.night-grading.enabled:${NIGHT_GRADING_ENABLED:true}}") String enabled,
            @Value("${assignments.night-grading.start:${NIGHT_GRADING_START:22:00}}") String windowStart,
            @Value("${assignments.night-grading.end:${NIGHT_GRADING_END:06:00}}") String windowEnd,
            @Value("${assignments.night-grading.zone:${NIGHT_GRADING_ZONE:America/Los_Angeles}}") String zone,
            @Value("${ai.house.preset:${AI_HOUSE_PRESET:}}") String preset,
            @Value("${ai.house.url:${AI_HOUSE_URL:}}") String url,
            @Value("${ai.house.models:${AI_HOUSE_MODELS:}}") String models,
            @Value("${ai.house.timeout-seconds:${AI_HOUSE_TIMEOUT_SECONDS:900}}") String timeoutSeconds,
            @Value("${ai.house.api-key:${AI_HOUSE_API_KEY:}}") String apiKey) {
        this.jdbc = jdbc; this.dataSource = dataSource; this.cipher = cipher;
        this.defaultApiKey = apiKey == null ? "" : apiKey.trim();
        String[] modelList = models == null ? new String[0] : models.split(",");
        Preset defaultPreset = Preset.parse(preset);
        if (defaultPreset == null) {
            defaultPreset = url == null || url.isBlank() ? Preset.NONE : Preset.CUSTOM;
        }
        defaults.put("enabled", enabled);
        defaults.put("window_start", windowStart);
        defaults.put("window_end", windowEnd);
        defaults.put("zone", zone);
        defaults.put("preset", defaultPreset.name());
        defaults.put("base_url", url == null || url.isBlank() ? defaultPreset.url() : url.trim());
        defaults.put("primary_model", modelList.length > 0 && !modelList[0].isBlank()
                ? modelList[0].trim() : defaultPreset.primaryModel());
        defaults.put("backup_model", modelList.length > 1 ? modelList[1].trim() : defaultPreset.backupModel());
        defaults.put("timeout_seconds", timeoutSeconds);
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String quote = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL") ? "`" : "\"";
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + quote + TABLE + quote
                    + " (name varchar(64) NOT NULL PRIMARY KEY, setting_value text,"
                    + " updated_by varchar(255), updated_at varchar(40))");
            available = true;
            cache = null;
        } catch (SQLException | DataAccessException e) {
            logger.error("Night grading settings storage unavailable; .env defaults are used.");
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public boolean isStorageAvailable() {
        return available;
    }

    public HouseSettings current() {
        Stored stored = load();
        Map<String, String> sources = new LinkedHashMap<>();
        java.util.function.Function<String, String> value = name -> {
            String saved = stored.values().get(name);
            sources.put(name, saved != null ? "saved" : ".env");
            return saved != null ? saved : defaults.get(name);
        };
        Preset preset = Preset.parse(value.apply("preset"));
        int timeout;
        try { timeout = Integer.parseInt(value.apply("timeout_seconds").trim()); }
        catch (NumberFormatException | NullPointerException e) { timeout = 900; }
        boolean keySaved = stored.values().get("api_key") != null;
        sources.put("api_key", keySaved ? "saved" : ".env");
        return new HouseSettings(!"false".equalsIgnoreCase(String.valueOf(value.apply("enabled")).trim()),
                value.apply("window_start"), value.apply("window_end"), value.apply("zone"),
                preset == null ? Preset.NONE : preset, trimSlash(value.apply("base_url")),
                blankToEmpty(value.apply("primary_model")), blankToEmpty(value.apply("backup_model")),
                timeout, keySaved || !defaultApiKey.isEmpty(), sources, stored.updatedBy(), stored.updatedAt());
    }

    /** The house server's API key in plain text, or "" when none is set. Never log or return it. */
    String apiKey() {
        String saved = load().values().get("api_key");
        if (saved == null) {
            return defaultApiKey;
        }
        try {
            return cipher.decrypt(KEY_CONTEXT, saved);
        } catch (AiConnectionException e) {
            logger.warn("Saved house model API key could not be decrypted; check AI_KEY_ENCRYPTION_KEY.");
            return "";
        }
    }

    /** True while the night grading window is open (and night grading is enabled). */
    public boolean isNightWindowOpen() {
        HouseSettings settings = current();
        return settings.enabled() && isInWindow(settings, clock.instant());
    }

    /** When the current or next window ends, for queue ETAs. */
    public Instant nightWindowEnd() {
        HouseSettings settings = current();
        try {
            ZoneId zone = ZoneId.of(settings.zone());
            LocalTime end = LocalTime.parse(settings.windowEnd());
            var now = clock.instant().atZone(zone);
            var candidate = now.with(end);
            return (candidate.isAfter(now) ? candidate : candidate.plusDays(1)).toInstant();
        } catch (DateTimeException e) {
            return clock.instant();
        }
    }

    static boolean isInWindow(HouseSettings settings, Instant instant) {
        try {
            LocalTime start = LocalTime.parse(settings.windowStart());
            LocalTime end = LocalTime.parse(settings.windowEnd());
            LocalTime now = instant.atZone(ZoneId.of(settings.zone())).toLocalTime();
            if (start.equals(end)) {
                return false;
            }
            return start.isBefore(end)
                    ? !now.isBefore(start) && now.isBefore(end)
                    : !now.isBefore(start) || now.isBefore(end); // Crosses midnight.
        } catch (DateTimeException e) {
            return false;
        }
    }

    /** Null when the changes are valid, otherwise a message for the admin. */
    public static String validate(Changes changes) {
        if (changes == null) {
            return "Settings are required.";
        }
        try {
            if (LocalTime.parse(changes.windowStart()).equals(LocalTime.parse(changes.windowEnd()))) {
                return "The window start and end must differ.";
            }
        } catch (DateTimeParseException | NullPointerException e) {
            return "Window times must look like 22:00.";
        }
        try {
            ZoneId.of(changes.zone());
        } catch (DateTimeException | NullPointerException e) {
            return "Unknown time zone.";
        }
        Preset preset = Preset.parse(changes.preset());
        if (preset == null) {
            return "Choose a model server from the list.";
        }
        if (changes.timeoutSeconds() == null || changes.timeoutSeconds() < 30 || changes.timeoutSeconds() > 3600) {
            return "Timeout must be between 30 and 3600 seconds.";
        }
        if (preset == Preset.NONE) {
            return null;
        }
        String url = preset.urlEditable() ? changes.baseUrl() : preset.url();
        if (!isHttpUrl(url)) {
            return "The server URL must start with http:// or https://.";
        }
        if (changes.primaryModel() == null || changes.primaryModel().isBlank()) {
            return "Choose a primary model (use Test connection to list them).";
        }
        return null;
    }

    static boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /** Saves validated changes. Refuses a new API key when AI_KEY_ENCRYPTION_KEY is not set. */
    @Transactional
    public void save(Changes changes, String updatedBy) {
        String error = validate(changes);
        if (error != null) {
            throw new AiConnectionException(400, "NIGHT_SETTINGS_INVALID", error);
        }
        requireAvailable();
        String newKey = changes.apiKey() == null || changes.apiKey().isBlank() ? null
                : cipher.isAvailable() ? cipher.encrypt(KEY_CONTEXT, changes.apiKey().trim()) : null;
        if (changes.apiKey() != null && !changes.apiKey().isBlank() && newKey == null) {
            throw new AiConnectionException(409, "AI_KEY_ENCRYPTION_UNAVAILABLE",
                    "An API key can't be saved until AI_KEY_ENCRYPTION_KEY is set on the server. Other settings were not saved.");
        }
        Preset preset = Preset.parse(changes.preset());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("enabled", String.valueOf(changes.enabled()));
        values.put("window_start", LocalTime.parse(changes.windowStart()).toString());
        values.put("window_end", LocalTime.parse(changes.windowEnd()).toString());
        values.put("zone", changes.zone().trim());
        values.put("preset", preset.name());
        values.put("base_url", preset == Preset.NONE ? "" : trimSlash(preset.urlEditable() ? changes.baseUrl() : preset.url()));
        values.put("primary_model", preset == Preset.NONE ? "" : blankToEmpty(changes.primaryModel()));
        values.put("backup_model", preset == Preset.NONE ? "" : blankToEmpty(changes.backupModel()));
        values.put("timeout_seconds", String.valueOf(changes.timeoutSeconds()));
        try {
            values.forEach((name, value) -> upsert(name, value, updatedBy));
            if (changes.clearApiKey()) {
                jdbc.update("DELETE FROM " + TABLE + " WHERE name = 'api_key'");
            } else if (newKey != null) {
                upsert("api_key", newKey, updatedBy);
            }
        } catch (DataAccessException e) {
            throw unavailable();
        } finally {
            cache = null;
        }
        logger.info("Night grading settings saved by {}: enabled={} window={}-{} {} preset={} models={}",
                updatedBy, changes.enabled(), changes.windowStart(), changes.windowEnd(), changes.zone(),
                preset, values.get("primary_model") + "," + values.get("backup_model"));
    }

    /** Removes every saved value, so the .env defaults apply again. */
    public void reset(String updatedBy) {
        requireAvailable();
        try {
            jdbc.update("DELETE FROM " + TABLE);
        } catch (DataAccessException e) {
            throw unavailable();
        } finally {
            cache = null;
        }
        logger.info("Night grading settings reset to defaults by {}", updatedBy);
    }

    private void upsert(String name, String value, String updatedBy) {
        String time = Instant.now().toString();
        if (jdbc.update("UPDATE " + TABLE + " SET setting_value = ?, updated_by = ?, updated_at = ? WHERE name = ?",
                value, updatedBy, time, name) == 0) {
            jdbc.update("INSERT INTO " + TABLE + " (name, setting_value, updated_by, updated_at) VALUES (?, ?, ?, ?)",
                    name, value, updatedBy, time);
        }
    }

    private Stored load() {
        Stored stored = cache;
        if (stored != null) {
            return stored;
        }
        if (!available) {
            return new Stored(Map.of(), null, null);
        }
        Map<String, String> values = new HashMap<>();
        String[] latest = new String[2];
        try {
            jdbc.query("SELECT name, setting_value, updated_by, updated_at FROM " + TABLE, rs -> {
                values.put(rs.getString(1), rs.getString(2));
                String at = rs.getString(4);
                if (at != null && (latest[1] == null || at.compareTo(latest[1]) > 0)) {
                    latest[0] = rs.getString(3);
                    latest[1] = at;
                }
            });
        } catch (DataAccessException e) {
            logger.warn("Night grading settings could not be read; using .env defaults.");
            return new Stored(Map.of(), null, null);
        }
        stored = new Stored(Map.copyOf(values), latest[0], latest[1]);
        cache = stored;
        return stored;
    }

    /** The preset list for the dropdown. */
    public static List<Map<String, Object>> presets() {
        return Arrays.stream(Preset.values()).map(preset -> Map.<String, Object>of(
                "id", preset.name(), "label", preset.label(), "url", preset.url(),
                "primaryModel", preset.primaryModel(), "backupModel", preset.backupModel(),
                "urlEditable", preset.urlEditable())).toList();
    }

    private void requireAvailable() {
        if (!available) {
            throw unavailable();
        }
    }

    private static AiConnectionException unavailable() {
        return new AiConnectionException(503, "NIGHT_SETTINGS_UNAVAILABLE",
                "Night grading settings storage is unavailable; the .env defaults are in use.");
    }

    private static String trimSlash(String url) {
        String value = blankToEmpty(url);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
