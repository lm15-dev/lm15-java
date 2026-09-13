package dev.lm15.types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A complete request to a foundation model (spec/types.md § Request).
 * {@code system} is either a string or a list of prompt parts, held as a
 * {@link SystemPrompt}; tool names are unique (INV-030) and
 * {@code tool_choice.allowed} names offered tools (INV-031).
 */
public record Request(String model, List<Message> messages, SystemPrompt system, List<Tool> tools, Config config) {
    public Request {
        if (model == null || model.isEmpty()) throw ValidationException.value("model is required");
        messages = Check.list(messages, "Request.messages");
        if (messages.isEmpty()) throw ValidationException.value("at least one message is required");
        tools = Check.list(tools, "Request.tools");
        if (config == null) config = Config.DEFAULT;
        Set<String> names = new HashSet<>();
        for (Tool t : tools) {
            if (!names.add(t.name())) throw ValidationException.value("Request.tools cannot contain duplicate tool names");
        }
        if (config.toolChoice() != null && !config.toolChoice().allowed().isEmpty()) {
            TreeSet<String> missing = new TreeSet<>(config.toolChoice().allowed());
            missing.removeAll(names);
            if (!missing.isEmpty()) {
                throw ValidationException.value("ToolChoice.allowed contains tools not present in Request.tools: " + missing);
            }
        }
    }

    public Request(String model, List<Message> messages) { this(model, messages, null, List.of(), Config.DEFAULT); }

    public static Builder builder(String model) { return new Builder(model); }

    /** The names of the offered tools, in order. */
    public List<String> toolNames() {
        List<String> out = new ArrayList<>(tools.size());
        for (Tool t : tools) out.add(t.name());
        return out;
    }

    public Request withMessages(List<Message> m) { return new Request(model, m, system, tools, config); }
    public Request withConfig(Config c) { return new Request(model, messages, system, tools, c); }
    public Request withModel(String m) { return new Request(m, messages, system, tools, config); }

    public static final class Builder {
        private final String model;
        private final List<Message> messages = new ArrayList<>();
        private SystemPrompt system;
        private final List<Tool> tools = new ArrayList<>();
        private Config config = Config.DEFAULT;

        Builder(String model) { this.model = model; }

        public Builder message(Message m) { messages.add(m); return this; }
        public Builder messages(List<Message> m) { messages.addAll(m); return this; }
        public Builder user(String text) { messages.add(Message.user(text)); return this; }
        public Builder system(String text) { system = SystemPrompt.of(text); return this; }
        public Builder system(List<? extends Part> parts) { system = SystemPrompt.of(parts); return this; }
        public Builder system(SystemPrompt s) { system = s; return this; }
        public Builder tool(Tool t) { tools.add(t); return this; }
        public Builder tools(List<? extends Tool> t) { tools.addAll(t); return this; }
        public Builder config(Config c) { config = c; return this; }
        public Request build() { return new Request(model, messages, system, tools, config); }
    }
}
