package dev.lm15.types;

import java.util.ArrayList;
import java.util.List;

/** How the model should use tools. {@code mode=none} forbids allowed/parallel (INV-028). */
public record ToolChoice(ToolChoiceMode mode, List<String> allowed, Boolean parallel) {
    public ToolChoice {
        Check.required(mode, "tool choice mode");
        allowed = Check.nonEmptyStrings(allowed, "ToolChoice.allowed", "ToolChoice.allowed must contain non-empty tool names");
        if (mode == ToolChoiceMode.NONE && (!allowed.isEmpty() || parallel != null)) {
            throw ValidationException.value("ToolChoice(mode='none') cannot specify allowed or parallel");
        }
    }

    public ToolChoice(ToolChoiceMode mode) { this(mode, List.of(), null); }

    public static final ToolChoice AUTO = new ToolChoice(ToolChoiceMode.AUTO);
    public static final ToolChoice REQUIRED = new ToolChoice(ToolChoiceMode.REQUIRED);
    public static final ToolChoice NONE = new ToolChoice(ToolChoiceMode.NONE);

    /** A choice naming tools (or Tool objects) explicitly. */
    public static ToolChoice fromTools(List<?> allowed, ToolChoiceMode mode, Boolean parallel) {
        List<String> names = new ArrayList<>();
        for (Object o : allowed) names.add(o instanceof Tool t ? t.name() : String.valueOf(o));
        return new ToolChoice(mode, names, parallel);
    }

    public ToolChoice withParallel(Boolean p) { return new ToolChoice(mode, allowed, p); }
}
