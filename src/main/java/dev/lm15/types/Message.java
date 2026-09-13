package dev.lm15.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A contribution to a conversation (spec/types.md § Message): a role and a
 * non-empty list of parts; role/part compatibility per INV-022..024.
 */
public record Message(Role role, List<Part> parts, List<ContinuationState> continuation) {
    public Message {
        Check.required(role, "role");
        parts = Check.list(parts, "Message.parts");
        if (parts.isEmpty()) throw ValidationException.value("Message requires at least one part");
        continuation = Check.continuation(continuation);
        validateParts(role, parts);
    }

    public Message(Role role, List<Part> parts) { this(role, parts, List.of()); }

    static void validateParts(Role role, List<Part> parts) {
        switch (role) {
            case TOOL -> {
                for (Part p : parts) {
                    if (!(p instanceof ToolResultPart)) throw ValidationException.type("tool messages may only contain ToolResultPart objects");
                }
            }
            case ASSISTANT -> {
                for (Part p : parts) {
                    if (p instanceof ToolResultPart) throw ValidationException.type("assistant messages cannot contain ToolResultPart objects");
                }
            }
            default -> {
                for (Part p : parts) {
                    if (Part.isPromptForbidden(p)) throw ValidationException.type(role.wire() + " messages cannot contain model/tool protocol parts");
                }
            }
        }
    }

    public static Message user(String text) { return new Message(Role.USER, Parts.normalize(text)); }
    public static Message user(Part part) { return new Message(Role.USER, Parts.normalize(part)); }
    public static Message user(List<? extends Part> parts) { return new Message(Role.USER, Parts.normalize(parts)); }

    public static Message developer(String text) { return new Message(Role.DEVELOPER, Parts.normalize(text)); }
    public static Message developer(Part part) { return new Message(Role.DEVELOPER, Parts.normalize(part)); }
    public static Message developer(List<? extends Part> parts) { return new Message(Role.DEVELOPER, Parts.normalize(parts)); }

    public static Message assistant(String text) { return new Message(Role.ASSISTANT, Parts.normalize(text)); }
    public static Message assistant(Part part) { return new Message(Role.ASSISTANT, Parts.normalize(part)); }
    public static Message assistant(List<? extends Part> parts) { return new Message(Role.ASSISTANT, Parts.normalize(parts)); }

    /** One tool result: {@code Message.tool(call.id(), "output")}. */
    public static Message tool(String callId, String output) {
        return new Message(Role.TOOL, List.of(Parts.toolResult(callId, output)));
    }

    public static Message tool(String callId, String output, boolean isError) {
        return new Message(Role.TOOL, List.of(Parts.toolResult(callId, output, isError)));
    }

    public static Message tool(String callId, List<Part> content) {
        return new Message(Role.TOOL, List.of(Parts.toolResult(callId, content)));
    }

    /** Explicit result parts. */
    public static Message tool(ToolResultPart... results) {
        return new Message(Role.TOOL, List.of(results));
    }

    public static Message tool(List<ToolResultPart> results) {
        return new Message(Role.TOOL, new ArrayList<>(results));
    }

    /** Several results at once, in map order (INV-025): {@code {call_id: output}}. */
    public static Message tool(Map<String, String> results) {
        List<Part> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(results).entrySet()) {
            parts.add(Parts.toolResult(e.getKey(), e.getValue()));
        }
        return new Message(Role.TOOL, parts);
    }

    /** All parts of a class. */
    public <P extends Part> List<P> partsOf(Class<P> cls) {
        List<P> out = new ArrayList<>();
        for (Part p : parts) if (cls.isInstance(p)) out.add(cls.cast(p));
        return out;
    }

    /** The first part of a class, or null. */
    public <P extends Part> P first(Class<P> cls) {
        for (Part p : parts) if (cls.isInstance(p)) return cls.cast(p);
        return null;
    }

    /** Text only when every part is a TextPart (joined with newlines); else null. */
    public String text() {
        for (Part p : parts) if (!(p instanceof TextPart)) return null;
        return Parts.textOf(parts);
    }

    public Message withContinuation(List<ContinuationState> c) { return new Message(role, parts, c); }
}
