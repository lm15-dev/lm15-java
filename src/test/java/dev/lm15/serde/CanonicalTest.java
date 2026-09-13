package dev.lm15.serde;

import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonValue;
import dev.lm15.types.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalTest {
    @Test void omissionRule() {
        Request r = new Request("m", List.of(Message.user("hi")));
        assertEquals("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}", Canonical.toJson(r).toJson());
        assertEquals("{}", Canonical.toJson(Usage.EMPTY).toJson());
        assertEquals("{\"type\":\"text\",\"text\":\"\"}", Canonical.toJson(new TextPart("")).toJson());
        assertEquals("{\"type\":\"function\",\"name\":\"f\",\"parameters\":{}}", Canonical.toJson(new FunctionTool("f", null, JsonObject.EMPTY)).toJson());
        assertEquals("{\"store\":false,\"logprobs\":0}", Canonical.toJson(Config.builder().store(false).logprobs(0).build()).toJson());
    }

    @Test void numberRuleAtTheBoundary() {
        Config c = Canonical.configFromJson(Json.parseObject("{\"temperature\":1,\"max_tokens\":2.0}"));
        assertEquals(1.0, c.temperature());
        assertEquals(2, c.maxTokens());
        assertEquals("{\"max_tokens\":2,\"temperature\":1.0}", Canonical.toJson(c).toJson());
        assertThrows(ValidationException.class, () -> Canonical.configFromJson(Json.parseObject("{\"top_k\":2.5}")));   // INV-007
        assertThrows(ValidationException.class, () -> Canonical.configFromJson(Json.parseObject("{\"max_tokens\":true}"))); // INV-003
        assertThrows(ValidationException.class, () -> Canonical.configFromJson(Json.parseObject("{\"tool_choice\":\"auto\"}"))); // INV-042
        assertNull(Canonical.configFromJson(Json.parseObject("{\"tool_choice\":null}")).toolChoice());
    }

    @Test void leniencies() {
        Part p = Canonical.partFromJson(Json.parseObject("{\"type\":\"tool_result\",\"id\":\"c\",\"content\":\"out\"}"));
        assertEquals("out", ((TextPart) ((ToolResultPart) p).content().get(0)).text());              // INV-041
        assertThrows(ValidationException.class, () -> Canonical.partFromJson(Json.parseObject("{\"type\":\"tool_result\",\"id\":\"c\",\"content\":\"\"}")));
        Message m = Canonical.messageFromJson(Json.parseObject("{\"role\":\"user\",\"parts\":[\"plain\",7]}"));
        assertEquals("plain\n7", m.text());                                                             // INV-047
        Reasoning r = Canonical.reasoningFromJson(Json.parseObject("{\"enabled\":false,\"budget\":9}"));
        assertTrue(r.isOff());                                                                          // INV-043
        assertEquals(ReasoningEffort.MEDIUM, Canonical.reasoningFromJson(Json.parseObject("{\"budget\":9}")).effort());
        assertThrows(ValidationException.class, () -> Canonical.partFromJson(Json.parseObject("{\"type\":\"nope\"}"))); // INV-044
        assertEquals(List.of("a"), Canonical.configFromJson(Json.parseObject("{\"stop\":\"a\"}")).stop());          // INV-020
    }

    @Test void deltaSerde() {
        assertEquals("{\"type\":\"text\",\"part_index\":0,\"text\":\"\"}", Canonical.toJson(new TextDelta("")).toJson());
        assertEquals("{\"type\":\"continuation\",\"provider\":\"p\",\"kind\":\"k\",\"data\":{}}", Canonical.toJson(new ContinuationDelta("p", "k", JsonObject.EMPTY, null)).toJson());
        assertEquals(0, ((TextDelta) Canonical.deltaFromJson(Json.parseObject("{\"type\":\"text\",\"text\":\"x\"}"))).partIndex()); // INV-045
    }

    @Test void contractVectorsRoundTrip() throws Exception {
        Path vectors = Path.of("../lm15-contract/serde/canonical.json");
        if (!Files.exists(vectors)) vectors = Path.of("/home/user/lm15-contract/serde/canonical.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(vectors), "contract checkout not present");
        JsonArray cases = Json.parseObject(Files.readString(vectors)).get("cases").asArray();
        List<String> failures = new ArrayList<>();
        for (JsonValue c : cases) {
            JsonObject kase = c.asObject();
            JsonObject value = kase.get("value").asObject();
            JsonObject back = Canonical.roundtrip(kase.get("kind").asString(), value);
            if (!strictEquals(value, back)) failures.add(kase.get("id").asString() + ": " + back.toJson());
        }
        assertEquals(List.of(), failures);
        assertEquals(36, Canonical.kinds().size());
    }

    /** Strict typed equality (1 != 1.0), which JsonObject.equals already is; spelled out for the vector loop. */
    private static boolean strictEquals(JsonValue a, JsonValue b) { return a.equals(b); }
}
