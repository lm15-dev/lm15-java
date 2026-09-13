# lm15-java

The Java port of lm15: one canonical request/response model over every
provider the [lm15-contract](https://github.com/lm15-dev/lm15-contract)
names, byte-exact against its corpus. Synchronous, zero runtime
dependencies (the JDK's `java.net.http` for HTTP and WebSocket), Java 21.

The contract commit this port is built against is in `CONTRACT_PIN`;
`harness/check.py` refuses to grade the port against any other commit.

## Status

**Contract-complete at the pin.** Every harness direction is green, with
zero failures and no skips added; the one skip per chat direction is a
corpus gap (`openai.computer_use` has no canonical request and no golden),
the same skip the reference reports.

| Direction | Contract surface | Result |
|---|---|---|
| `serde` | spec/types.md, spec/vocabularies.md, spec/invariants.md, docs/serde-rules.md; all 36 kinds | 115 / 0 |
| `error` | ErrorCode + class hierarchy; `normalize_error` per provider | 84 / 0 |
| `auth` | spec/auth.md AUTH-1/2/5/7/8/10 (core, 32) and the three cloud chains, AUTH-11 (cloud, 11) | 43 / 0 |
| `token` | SigV4 (34 vectors), RS256 JWTs, token exchanges | 43 / 0 |
| `request` | the four dialects, request side; MAP-5..8, MAP-10; hosts, presets | 366 / 0 (1 skip) |
| `response` | the four dialects, response side; MAP-1..4, MAP-9 | 302 / 0 (1 skip) |
| `stream` | SSE decoding, MAP-3/4 coalescing, MAP-9 assembly and its refusal | 40 / 0 |
| `router` | the three rungs, precedence, `unknown_model` / `ambiguous_model` | 22 / 0 |
| `models` | `list_models` on every provider | 34 / 0 |
| `files`, `batch`, `cache` | the three surfaces, multipart byte for byte, MAP-11 id escaping | 48 / 0, 41 / 0, 11 / 0 |
| `generation`, `video` | image and speech generation, video jobs | 20 / 0, 27 / 0 |
| `live` | the websocket codec (OpenAI Realtime, Gemini Live) | 24 / 0 |
| `ingest` | MAP-12: a Chat Completions request body → `Request` under one preset's spellings; the response door | 160 / 0 |

Beyond the harness: **165 unit and integration tests** (`mvn test`) and
368 independent request comparisons (23 probes × 8 providers × complete /
stream). Of these, 348 match Python; 20 verify documented corrections to its
lost citations and unsupported media. Nothing is skipped: the corrected
results are checked explicitly against MAP-10, and every reference difference
is recorded. See [CONFORMANCE.md](CONFORMANCE.md) and
[history content](docs/history-content.md) for evidence and reproduction.
CI also tests the standalone JAR outside the source checkout, on Linux
(Java 21/25) and macOS (Java 21).

### Not exercised live, stated

No provider was called from this port: batch jobs, image and video
generation, the cloud credential chains, the OAuth refresh wire, the xAI
device-code login and the websocket sessions are proven by the pinned
lifecycles, vectors and transcripts of the corpus and by scripted-transport
tests, not by a receipt from a real server. The first live smoke is the
next step.

## Gates

```bash
mvn -B package                        # unit tests + target/lm15.jar
python3 tools/check_contract.py --contract ../lm15-contract --direction all
python3 tools/differential.py --contract ../lm15-contract \
  --python-repo ../lm15-python --verify-documented-fixes
```

The contract is never copied into this repository: the serde test
(`CanonicalTest`) replays `serde/canonical.json` from the sibling checkout
when it is present and skips itself otherwise.

## Quick start (a call)

```java
import dev.lm15.*;
import dev.lm15.router.LMRouter;
import dev.lm15.stream.ResponseStream;
import dev.lm15.types.*;

LMRouter router = new LMRouter();   // keys from the environment (AUTH-1)
Request request = Request.builder("groq:openai/gpt-oss-20b")   // or "claude-haiku-4-5", "gpt-4.1-mini"
    .user("hi")
    .config(Config.builder().maxTokens(100).build())
    .build();

// One call.
Response response = router.complete(request);
System.out.println(response.text());

// Streamed: text as it arrives, then the same Response `complete` returns.
try (ResponseStream rs = router.responseStream(request)) {
    for (String text : rs) System.out.print(text);
    Response same = rs.response();
}

// How was it routed? `resolve` is pure: no network, no files, no secrets.
System.out.println(router.resolve("grok-4"));
```

Direct providers use the same credential discovery as the router, or accept
an explicit key/value/callback. A callback is invoked once per request, never
while constructing the client. Cloud credential exchanges use the configured
transport and clock. For example, a provider directly with a key:

```java
ProviderLM lm = OpenAILM.create(System.getenv("OPENAI_API_KEY"));
Response response = lm.complete(Request.builder("gpt-4.1-mini").system("You are terse.").user("Say hello in three words.").build());
```

Every OpenAI-compatible server through the Chat Completions dialect; a
compat preset name bundles that server's wire quirks and its address:

```java
ProviderLM ollama = OpenAIChatLM.builder().apiKey("ollama").preset("ollama").build();  // http://localhost:11434/v1
```

## Tools: the full round-trip

```java
FunctionTool weather = new FunctionTool("get_weather", "Get the current weather for a city.",
    Json.obj("type", "object", "properties", Json.obj("city", Json.obj("type", "string")), "required", Json.arr("city")));

Request first = Request.builder("gpt-4.1-mini").user("What is the weather in Montreal?").tool(weather)
    .config(Config.builder().toolChoice(ToolChoice.REQUIRED.withParallel(false)).build()).build();
Response response = lm.complete(first);
ToolCallPart call = response.toolCalls().get(0);
String result = "Sunny and 22°C in " + call.input().get("city").asString();

List<Message> messages = new ArrayList<>(first.messages());
messages.add(response.message());
messages.add(Message.tool(call.id(), result));
Response answer = lm.complete(first.withMessages(messages).withConfig(Config.builder().toolChoice(ToolChoice.NONE).build()));
```

The schema is written by you (`FunctionTool.parameters` is JSON Schema);
no derivation from a method signature — the family's stated deviation for
every non-Python port. lm15 never runs the loop for you.

## Stated deviations

Each names the rule it deviates from (playbooks/port.md rule 8). The wire
is not affected unless the entry says so.

- **`wait(WaitOptions)` on `BatchJob` / `VideoJob`** (api-family § Beyond
  chat): Java reserves the no-argument `wait()` on every object, so the
  handle's `wait` takes an options value (`WaitOptions.DEFAULT`), with
  `waitDone()` as the no-argument spelling. A deadline past is
  `java.util.concurrent.TimeoutException` (the language's own timeout
  type, by rule).
- **Records, not keyword arguments** (api-family § Types and serde):
  canonical types are Java records; the many-field ones (`Config`,
  `Usage`, `CacheConfig`, `Request`, `FileInfo`, `LiveConfig`) carry a
  builder, which is how a field added later stays "keyword-only" (rule 6).
  `Request.system` is a `SystemPrompt` (a string or prompt parts); nullable
  fields are absent when `null`, never `Optional`.
- **Serde is `Canonical.toJson(x)` / `Canonical.xFromJson(json)`**: static
  functions over one `JsonValue` model (`dev.lm15.json`) that keeps `1` and
  `1.0` apart and object key order intact; opaque payloads are
  `JsonObject`s and round-trip verbatim.
- **Validation is `ValidationException`** (an `IllegalArgumentException`)
  whose `protocolName()` is `ValueError` or `TypeError`, the native names
  the reference raises and the vet protocol reports.
- **Sync only** (api-family rule 4): `complete` blocks, `stream` returns an
  `Iterator` that is also `AutoCloseable`; there is no async mirror. Use a
  thread or an executor.
- **Credential provider is the single-method interface** (`CredentialProvider`),
  the Go/Rust shape; a plain string is the `ApiKey` shorthand.
- **Router rung 0 and catalog discovery**: NEVER, as in every non-Python
  port — the router takes a data catalog (`RouterConfig.catalog`).
- **`ProviderProfile` / `EndpointProfile`**: never ported (contract decision
  2026-09-11); the same facts are `preset(...)` + `baseUrl(...)`.
- **A `provider:` prefix on `Request.model` given to a direct adapter is
  removed** when it names that adapter's own provider (`OpenAILM` sends
  `gpt-5.4` for `openai:gpt-5.4`), as the Rust port does; the reference
  sends the string verbatim. The router strips it on every port.
- **Stored logins yield a `BearerToken`** (AUTH-2) where the reference
  coerces the stored string to an `ApiKey`; every subscription door lists
  `bearer`, so the wire is identical. The injected provider transport/clock
  governs cloud exchanges, not the borrowed stores' dedicated OAuth refresh
  implementation.
- **History content follows MAP-10 rather than inherited omissions.**
  Responses preserves assistant images/files; unsupported combinations raise
  before sending. Citations retain their title, URL and quote. The documented
  differences from Python are tested, not ignored (see `docs/history-content.md`).
- **`stream` returns `ProviderLM.EventStream`** (an `Iterator` that is
  `AutoCloseable`) rather than a language-level lazy sequence; closing it
  releases the connection. `ResponseStream` wraps it.

## Not implemented, stated

- The loopback OAuth callback listener (AUTH-9's third primitive): no
  flow this port owns needs one (xAI is device-code); PKCE and RFC 8628
  polling are shipped. On demand, per the family decision.
- Streaming a chat `Request` over the Realtime websocket for `-realtime`
  models (the reference's websocket transport mode): the codec is ported
  and pinned; the socket driver for that mode is not.
- `aws-event-stream` framing (Bedrock Converse, phase 2), `aws login`
  refresh, Azure Service Fabric managed identity, GCP `external_account`
  with an AWS source: each is a typed `NotConfiguredError` naming the fix,
  the same gap every port states.
- `surface_dump` reports the canonical record fields and vocabularies by
  reflection; it is not a module gate (the ratchet runs on the reference).

## Layout

See `PORTING.md` for the package map and the conventions a contributor
follows; the vet shim is `dev.lm15.vet.Main` (`java -jar target/lm15.jar`).
