# Porting notes — how this Java port is laid out

Read `../lm15-contract/playbooks/port.md` first. This file is the map of the
Java code and the conventions every module follows so the port reads as one
family member (`playbooks/api-family.md`).

## Layout

| Package | What |
|---|---|
| `dev.lm15.json` | `JsonValue` sealed sum with int/float fidelity (`JsonInt` / `JsonFloat`), order-keeping `JsonObject`, `Json.parse/write/obj/arr`, `JsonBuilder` (`put`, `putIfPresent`, `putIfNonEmpty`). Every opaque payload is a `JsonObject`. |
| `dev.lm15.types` | Canonical types as records; sealed `Part` / `Delta` / `StreamEvent` / `LiveClientEvent` / `LiveServerEvent`; vocabularies as enums with `wire()` / `fromWire()`; `ValidationException` (ValueError / TypeError). Absent = `null`. Lists are immutable copies. |
| `dev.lm15.serde.Canonical` | `toJson(x)` / `xFromJson(JsonObject)` per type + `Canonical.kind(name)` for the vet. Read helpers `readString/readInt/readDouble/readBool/readObject/readArray/readStrings` implement the Number rule at the boundary. |
| `dev.lm15.errors` | `LM15Error` tree with the family's class names; `ErrorMeta(provider, providerCode, status, requestId, retryAfter)`; `Errors.mapHttpError`, `Errors.attachMetadata`. |
| `dev.lm15.auth` | `Credential` sum (ApiKey/BearerToken/AwsCredentials), `CredentialProvider`, `AccessPolicy` + the `Access` table (AUTH-10), `Access.selectScheme` / `authHeader`, `CredentialStores` (stored-login loaders register here), `Rfc3339`. |
| `dev.lm15.wire` | `WireRequest` (what a dialect builds: path under the base URL, params, headers, JSON or raw body, `endpoint`, `model`), `TransportRequest` (what goes on the wire), `BuildContext` (provider, policy, settings, compat, baseUrl, wire model, accountId), `Wire.emit` (auth header + host rewrites + SigV4), `Wire.pathId` (MAP-11), `Wire.normalize` (the vet's request shape), `Clock`. |
| `dev.lm15.cloud` | `Hosts` (settings resolution, base-URL rendering, host rewrites), `SigV4` (seam; the signer lands with the cloud module). |
| `dev.lm15.compat` | `Compat` marker interface; each dialect defines its own compat record + preset table; `PresetAddresses` (a preset name supplies its server's address). |
| `dev.lm15.dialects` | `Dialect` interface (build / parseResponse / parseStreamEvent / normalizeError + every surface hook, defaults refuse), `Dialects` registry (reflective lookup by key: `openai-responses`, `openai-chat`, `xai`, `anthropic`, `gemini`), `Common` (shared helpers: `partsToText`, `mediaDataUri`, `checkToolResultMedia`, `partToOpenAIInput`, `toolResultOutputOpenAI`, `openaiTokenLogprobs`, `isoUtc`, `multipartFormBody`, `modelInfosFromEntries`, `recordUnmapped`, `EFFORT_THINKING_BUDGETS`). |
| `dev.lm15.ProviderLM` | The bound adapter: `buildRequest`, `parseResponse`, `replayStream`, `normalizeError`, `complete`, `stream`, `responseStream`, every surface driver, `ProviderLM.builder(definition)`. |
| `dev.lm15.registry` | `Registry.PROVIDERS` (the 31 definitions), `ProviderDefinition`, `DialectId`. |
| `dev.lm15.stream` | `Coalescer` (MAP-3/4), `StreamAccumulator` (MAP-9), `Streams.materialize`, `ResponseStream`. |
| `dev.lm15.sse` | `Sse.parse(bytes)` / `Sse.iterate(stream)` → `SseEvent(event, data)`. |
| `dev.lm15.transport` | `HttpResponse`, `Transport`, `HttpTransport` (java.net.http). |
| `dev.lm15.jobs` | `BatchJob`, `VideoJob`, `WaitOptions`. |
| `dev.lm15.live` | `LiveCodec` (setupFrames / encode / decode), `LiveSession`, `Turn`. |
| `dev.lm15.vet` | The shim: `Main` (framing), `AdapterOps` (build/parse ops), `AuthOps`, `RouterOps`, `SurfaceDump`. |

## Conventions

- **A dialect is a stateless class implementing `Dialect`**, one per package:
  `dev.lm15.dialects.openai.OpenAIResponsesDialect`,
  `dev.lm15.dialects.openaichat.OpenAIChatDialect` (+ `XaiDialect`),
  `dev.lm15.dialects.anthropic.AnthropicDialect`, `dev.lm15.dialects.gemini.GeminiDialect`.
  `Dialects` finds them reflectively by those exact class names; a public no-arg constructor is required.
- `build()` returns a `WireRequest` with `path` under the base URL (e.g. `/messages`, `/chat/completions`, `/responses`, `/models/{model}:generateContent`), sets `endpoint` (`messages`, `chat/completions`, `responses`, `generateContent`) and `model` (the wire model) so cloud hosts can rewrite the path; policy static headers (`cx.policy().headers()`) are merged by the dialect's own rule. Never add the credential header — `Wire.emit` does.
- Wire bodies are `JsonObject`s built with `Json.obj(...)` / `JsonBuilder` in the **reference's key order** (a SigV4 signature covers the bytes; goldens compare parsed JSON, but keep the order anyway).
- Numbers: `JsonInt.of(n)` for ints, `new JsonFloat(d)` for floats. A canonical float field (temperature) that a provider wire wants in proto3-JSON integer form is the dialect's business.
- Refusals: `throw new UnsupportedFeatureError(message, ErrorMeta.of(cx.provider()))` before any wire. Provider errors: `Errors.mapHttpError(status, message, ErrorMeta.of(provider).withProviderCode(code))` then refine class by the provider's own code table. Messages are never pinned; class + code + provider_code are.
- Unmapped response content: collect `Common.recordUnmapped(recorder, path, type)` and, when non-empty, put `_lm15_unmapped` into the Response's `provider_data` (the vet surfaces it; the harness fails any non-empty list).
- Response `provider_data` is the whole wire body (`JsonObject`); stream end events carry the frame that supplied usage (MAP-3 D9).
- `Common.parseJsonObject` for tool-call arguments on the complete path; the accumulator does the stream path.
- Copy tables as data (port.md rule 2): compat presets, model-class detectors, finish-reason maps, error-code maps — from `lm15-python/lm15/compat.py` and the dialect modules under `lm15-python/lm15/providers/`. The Rust port (`lm15-rs/src/dialects/`) is the closest typed sibling; the TypeScript port is the most compact.

## Running the harness

```bash
mvn -q -B -DskipTests package            # target/lm15.jar (the shim)
cd ../lm15-contract
python3 harness/check.py --shim java --direction request    # or response, stream, error, serde, auth, token, models, live, files, batch, generation, video, cache, router, ingest, all
python3 harness/check.py --shim java --direction request --case anthropic.basic_text
```

Reports land in `lm15-contract/harness/reports/<direction>.json`; failed cases carry the first JSON diff path. The corpus is read-only to the port (port.md rule 1).
