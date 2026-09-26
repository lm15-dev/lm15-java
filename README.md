# lm15 for Java (early port)

lm15 is one request and response model for every major AI model provider:
write a request once and send it to OpenAI, Anthropic, Gemini, xAI, Groq,
DeepSeek, OpenRouter, a cloud or a local model, by changing the model
string. Each language implements it separately and is graded by one shared
[contract](https://github.com/lm15-dev/lm15-contract).

**Early port, not published.** Written against the lm15 contract of
2026-09-11 (`cfed007`, in `CONTRACT_PIN`), where it passed every check:
1,380 of 1,380 in 16 directions, plus 165 of its own tests, on Java 21 and
25. It has not been updated since, and the contract has grown (sign-in,
judgments, ordered JSON checks, new providers). No provider has been
called from this port.

lm15 is released in [Python](https://github.com/lm15-dev/lm15-python) (1.0.1, stable),
[TypeScript](https://github.com/lm15-dev/lm15-ts), [Rust](https://github.com/lm15-dev/lm15-rs)
and [Go](https://github.com/lm15-dev/lm15-go) (release candidates). Guides:
[lm15.dev](https://lm15.dev/docs/). For production, use one of those.

## What's here

Synchronous, zero runtime dependencies (the JDK's `java.net.http` for
HTTP and WebSocket), Java 21 or newer: the router, the four provider
dialects, streaming, tools, errors, credentials and cloud credential
chains, files, batches, caches, media generation, video and realtime
sessions.

## Try it

```bash
mvn -B package        # unit tests + target/lm15.jar
```

```java
LMRouter router = new LMRouter();   // keys from the environment
Request request = Request.builder("anthropic:claude-haiku-4-5")
    .user("What eats acorns at night?")
    .build();
System.out.println(router.complete(request).text());
```

## Conformance

```bash
python3 tools/check_contract.py --contract ../lm15-contract --direction all
```

The full guide written with this port, with every API and its stated
differences from the Python reference: [docs/guide.md](docs/guide.md).

## License

See [LICENSE](LICENSE).
