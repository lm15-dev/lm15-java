package dev.lm15.sse;

/** One Server-Sent Event: the optional event name and the joined data lines. */
public record SseEvent(String event, String data) {}
