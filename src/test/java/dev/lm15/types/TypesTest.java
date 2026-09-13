package dev.lm15.types;

import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypesTest {
    @Test void toolResultContentRules() {
        assertThrows(ValidationException.class, () -> new ToolResultPart("c1", List.of()));                 // INV-014
        assertThrows(ValidationException.class, () -> new ToolResultPart("c1", List.of(new ThinkingPart("x")))); // INV-013
        ToolResultPart empty = Parts.toolResult("c1", "");
        assertEquals(1, empty.content().size());                                                               // INV-014 via INV-021
    }

    @Test void refusalAndCitationInvariants() {
        assertThrows(ValidationException.class, () -> new RefusalPart(""));                                   // INV-016
        assertThrows(ValidationException.class, () -> new CitationPart(null, null, null));                    // INV-017
        assertEquals("", new TextPart("").text());                                                            // INV-015
    }

    @Test void mediaAddressing() {
        assertThrows(ValidationException.class, () -> new ImagePart("image/png", null, null, null, null, null, List.of())); // INV-011
        assertThrows(ValidationException.class, () -> new ImagePart("image/png", "abc", "http://x", null, null, null, List.of()));
        assertThrows(ValidationException.class, () -> new ImagePart("image/png", "not base64!", null, null, null, null, List.of())); // INV-012
        ImagePart ok = Parts.image(Parts.Address.ofData("data:image/png;base64,QUJD RA=="));
        assertEquals("image/png", ok.mediaType());
        assertThrows(ValidationException.class, () -> new AudioPart("", "QUJD", null, null, null, List.of()));   // INV-010
    }

    @Test void messageRoleRules() {
        ToolResultPart result = Parts.toolResult("c1", "ok");
        assertThrows(ValidationException.class, () -> new Message(Role.USER, List.of(result)));                // INV-024
        assertThrows(ValidationException.class, () -> new Message(Role.ASSISTANT, List.of(result)));           // INV-023
        assertThrows(ValidationException.class, () -> new Message(Role.TOOL, List.of(new TextPart("x"))));     // INV-022
        assertThrows(ValidationException.class, () -> new Message(Role.USER, List.of()));
        assertEquals("a\nb", Message.user(List.of(new TextPart("a"), new TextPart("b"))).text());
        Message tool = Message.tool(Map.of("c1", "out"));
        assertEquals(Role.TOOL, tool.role());
    }

    @Test void configFamilyConsistency() {
        assertThrows(ValidationException.class, () -> new Reasoning(ReasoningEffort.OFF, 1024, null));          // INV-026
        assertThrows(ValidationException.class, () -> CacheConfig.builder().mode(CacheMode.OFF).key("k").build()); // INV-027
        assertThrows(ValidationException.class, () -> CacheConfig.builder().prefix(CachePrefix.STABLE).prefixUntilIndex(1).build());
        assertThrows(ValidationException.class, () -> new ToolChoice(ToolChoiceMode.NONE, List.of("f"), null)); // INV-028
        assertThrows(ValidationException.class, () -> Config.builder().temperature(-1.0).build());
        assertThrows(ValidationException.class, () -> Config.builder().topP(1.5).build());
        assertThrows(ValidationException.class, () -> Config.builder().maxTokens(0).build());
        assertNull(Config.builder().extensions(JsonObject.EMPTY).build().extensions());                       // INV-004
        assertTrue(Config.DEFAULT.isDefault());
    }

    @Test void responseFormatShapes() {
        assertDoesNotThrow(() -> Config.builder().responseFormat(Json.obj("type", "json_object")).build());
        assertDoesNotThrow(() -> Config.builder().responseFormat(Json.obj("type", "json_schema", "schema", Json.obj("type", "object"), "name", "r", "strict", true)).build());
        assertThrows(ValidationException.class, () -> Config.builder().responseFormat(Json.obj("type", "json_schema")).build());
        assertThrows(ValidationException.class, () -> Config.builder().responseFormat(Json.obj("format", "json")).build());
        assertThrows(ValidationException.class, () -> Config.builder().responseFormat(Json.obj("type", "json_object", "schema", Json.obj())).build());
        assertThrows(ValidationException.class, () -> Config.builder().responseFormat(Json.obj("type", "json_schema", "schema", Json.obj(), "strict", 1)).build()); // INV-050 TypeError
    }

    @Test void requestToolRules() {
        FunctionTool f = new FunctionTool("f");
        assertThrows(ValidationException.class, () -> new Request("m", List.of(Message.user("hi")), null, List.of(f, new FunctionTool("f")), Config.DEFAULT)); // INV-030
        assertThrows(ValidationException.class, () -> new Request("m", List.of(Message.user("hi")), null, List.of(f),
            Config.builder().toolChoice(new ToolChoice(ToolChoiceMode.AUTO, List.of("g"), null)).build()));   // INV-031
        assertThrows(ValidationException.class, () -> new Request("", List.of(Message.user("hi"))));
        assertThrows(ValidationException.class, () -> new Request("m", List.of()));
        assertEquals(FunctionTool.DEFAULT_PARAMETERS, f.parameters());                                        // INV-033
    }

    @Test void usageSemantics() {
        Usage u = new Usage(3, 4);
        assertEquals(7, u.totalTokens());                                                                     // INV-029
        assertNull(new Usage(3, null).totalTokens());
        assertEquals(10, new Usage(3, 4, 10, null, null, null, null, null).totalTokens());
        assertThrows(ValidationException.class, () -> new Usage(-1, null));
        assertTrue(Usage.EMPTY.isEmpty());
        assertNull(new Usage(1, 2).plus(new Usage(null, 3)).inputTokens());                                    // LIVE-2
    }

    @Test void responseShape() {
        Message m = Message.assistant(List.of(new TextPart("a"), new CitationPart("http://x", null, null)));
        Response r = new Response(null, "m", m, FinishReason.STOP, null, null, null);
        assertEquals("a", r.text());
        assertEquals(1, r.citations().size());
        assertThrows(ValidationException.class, () -> new Response(null, "m", Message.user("x"), FinishReason.STOP, null, null, null)); // INV-036
        assertEquals(42, new Response(null, "m", Message.assistant(" 42 "), FinishReason.STOP, null, null, null).parseJson().asInt());
    }

    @Test void batchEntryOutcomes() {
        Response r = new Response(null, "m", Message.assistant("x"), FinishReason.STOP, null, null, null);
        assertDoesNotThrow(() -> new BatchEntry(0, BatchOutcome.SUCCEEDED, r, null));
        assertThrows(ValidationException.class, () -> new BatchEntry(0, BatchOutcome.SUCCEEDED, null, null));
        assertThrows(ValidationException.class, () -> new BatchEntry(0, BatchOutcome.CANCELLED, r, null));
        assertThrows(ValidationException.class, () -> new BatchEntry(-1, BatchOutcome.EXPIRED, null, null));
    }

    @Test void cachedPrefixBuildsTheSeam() {
        Request prefix = new Request("m", List.of(Message.user("doc")));
        CachedPrefix cached = new CachedPrefix(prefix, null);
        Request r = cached.request("q");
        assertEquals(2, r.messages().size());
        assertEquals(0, r.config().cache().prefixUntilIndex());
        assertThrows(ValidationException.class, () -> new CachedPrefix(prefix.withConfig(Config.builder().maxTokens(3).build()), null));
    }
}
