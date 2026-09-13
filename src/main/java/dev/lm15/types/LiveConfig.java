package dev.lm15.types;

import dev.lm15.json.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A live (realtime) session configuration. */
public record LiveConfig(String model, SystemPrompt system, List<Tool> tools, String voice, AudioFormat inputFormat,
                         AudioFormat outputFormat, JsonObject extensions) {
    public LiveConfig {
        if (model == null || model.isEmpty()) throw ValidationException.value("model is required");
        tools = Check.list(tools, "LiveConfig.tools");
        Set<String> names = new HashSet<>();
        for (Tool t : tools) if (!names.add(t.name())) throw ValidationException.value("LiveConfig.tools cannot contain duplicate tool names");
        Check.optNonEmpty(voice, "LiveConfig.voice");
        extensions = Check.extensions(extensions);
    }

    public LiveConfig(String model) { this(model, null, List.of(), null, null, null, null); }

    public static Builder builder(String model) { return new Builder(model); }

    public static final class Builder {
        private final String model;
        private SystemPrompt system;
        private List<Tool> tools = List.of();
        private String voice;
        private AudioFormat inputFormat, outputFormat;
        private JsonObject extensions;

        Builder(String model) { this.model = model; }
        public Builder system(String v) { system = SystemPrompt.of(v); return this; }
        public Builder system(SystemPrompt v) { system = v; return this; }
        public Builder tools(List<? extends Tool> v) { tools = List.copyOf(v); return this; }
        public Builder voice(String v) { voice = v; return this; }
        public Builder inputFormat(AudioFormat v) { inputFormat = v; return this; }
        public Builder outputFormat(AudioFormat v) { outputFormat = v; return this; }
        public Builder extensions(JsonObject v) { extensions = v; return this; }
        public LiveConfig build() { return new LiveConfig(model, system, tools, voice, inputFormat, outputFormat, extensions); }
    }
}
