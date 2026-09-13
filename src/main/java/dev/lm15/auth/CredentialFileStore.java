package dev.lm15.auth;

import dev.lm15.json.Json;
import dev.lm15.json.JsonException;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.types.ValidationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The lm15-owned credential store (spec/auth.md AUTH-8): a locked, atomic,
 * private (0600) JSON file keyed by provider id — {@code $LM15_CREDENTIALS_PATH},
 * else {@code $XDG_CONFIG_HOME/lm15/credentials.json}, else
 * {@code ~/.config/lm15/credentials.json}. {@link #mutate} is the only
 * compound write path: a serialized read-modify-write under the
 * cross-process lock, so refresh flows re-check state before writing.
 */
public final class CredentialFileStore {
    private final Path path;

    public CredentialFileStore(Path path) { this.path = path != null ? path : defaultCredentialsPath(); }

    public CredentialFileStore() { this(null); }

    public Path path() { return path; }

    @Override public String toString() { return "CredentialFileStore(path=" + path + ")"; }

    public static Path defaultCredentialsPath() { return defaultCredentialsPath(System.getenv(), null); }

    /** The store path under a given environment and home (the doctor's hermetic view). */
    public static Path defaultCredentialsPath(Map<String, String> env, Path home) {
        Map<String, String> e = env == null ? System.getenv() : env;
        Path h = home != null ? home : CredentialLock.home(e);
        String override = e.get("LM15_CREDENTIALS_PATH");
        if (override != null && !override.isEmpty()) return expand(override, h);
        String configHome = e.get("XDG_CONFIG_HOME");
        Path base = configHome != null && !configHome.isEmpty() ? expand(configHome, h) : h.resolve(".config");
        return base.resolve("lm15").resolve("credentials.json");
    }

    static Path expand(String text, Path home) {
        if (text.equals("~")) return home;
        if (text.startsWith("~/")) return home.resolve(text.substring(2));
        return Path.of(text);
    }

    private JsonObject readAll() {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return JsonObject.EMPTY;
        } catch (IOException e) {
            throw ValidationException.value("Credential store at " + path + " is unreadable.");
        }
        JsonValue data;
        try {
            data = Json.parse(text);
        } catch (JsonException e) {
            throw ValidationException.value("Credential store at " + path + " is not valid JSON.");
        }
        if (!(data instanceof JsonObject o)) throw ValidationException.value("Credential store at " + path + " must be a JSON object.");
        return o;
    }

    /** The stored credential for {@code provider}, or null. */
    public JsonObject read(String provider) {
        return readAll().optObject(provider);
    }

    /** Provider ids with stored credentials, sorted. Never returns values. */
    public List<String> list() {
        List<String> out = new ArrayList<>(readAll().keys());
        Collections.sort(out);
        return out;
    }

    public void write(String provider, JsonObject credential) {
        mutate(provider, current -> credential);
    }

    public void delete(String provider) {
        CredentialLock.withLock(path, () -> {
            JsonObject data = readAll();
            if (data.has(provider)) CredentialLock.writePrivateJsonAtomic(path, data.without(provider));
        });
    }

    /**
     * Serialized read-modify-write for one provider's credential. {@code fn}
     * receives the current entry (or null) as read inside the lock and
     * returns the new entry, or null to leave it unchanged. Returns the
     * post-write entry.
     */
    public JsonObject mutate(String provider, Function<JsonObject, JsonObject> fn) {
        return CredentialLock.withLock(path, CredentialLock.DEFAULT_TIMEOUT, () -> {
            JsonObject data = readAll();
            JsonObject current = data.optObject(provider);
            JsonObject replacement = fn.apply(current);
            if (replacement == null) return current;
            CredentialLock.writePrivateJsonAtomic(path, data.with(provider, replacement));
            return replacement;
        });
    }
}
