package dev.lm15.compat;

import dev.lm15.auth.Access;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.registry.DialectId;

import java.util.Map;

/**
 * A compat preset name supplies its server's address, never the cloud's
 * (api-family 2026-09-11): a preset with no address row in the chosen
 * dialect is refused at construction, naming {@code base_url}.
 */
public final class PresetAddresses {
    private PresetAddresses() {}

    /** Spelling aliases accepted as preset names. */
    public static String canonicalPreset(String name) {
        return switch (name) {
            case "lm-studio", "lm_studio" -> "lmstudio";
            case "z.ai" -> "zai";
            case "dashscope_qwen", "dashscope-qwen" -> "qwen";
            case "responses" -> "openai";
            default -> name.replace('-', '_');
        };
    }

    private static Map<String, String> table(DialectId dialect) {
        return switch (dialect) {
            case OPENAI_CHAT -> Access.OPENAI_CHAT_PRESET_BASE_URLS;
            case OPENAI_RESPONSES -> Access.OPENAI_RESPONSES_PRESET_BASE_URLS;
            case ANTHROPIC -> Access.ANTHROPIC_PRESET_BASE_URLS;
            case GEMINI -> Map.of();
        };
    }

    public static String dialectDefault(DialectId dialect) {
        return switch (dialect) {
            case OPENAI_CHAT, OPENAI_RESPONSES -> "https://api.openai.com/v1";
            case ANTHROPIC -> "https://api.anthropic.com/v1";
            case GEMINI -> "https://generativelanguage.googleapis.com/v1beta";
        };
    }

    public static String forPreset(DialectId dialect, String preset, String provider) {
        String url = table(dialect).get(canonicalPreset(preset));
        if (url == null) {
            throw new NotConfiguredError(provider + ": the '" + preset + "' preset names no server address on the " + dialect.wire()
                + " dialect; pass base_url= to say where that server is (a request addressed to a named server is never sent to the dialect's cloud default)",
                ErrorMeta.of(provider));
        }
        return url;
    }
}
