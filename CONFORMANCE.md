# Java validation — 2026-09-13

Contract: `cfed00771dffef2a218b5549151950bf5060ca06` (`CONTRACT_PIN`).
Python reference: `3bbbd3ee1bab40a1a61cc74db120c4a8eb7eb22b`.

## Results

- **165 Java tests**, no failures/errors/skips, locally on OpenJDK 21.0.12.
- **1,380 shared contract checks**, zero failures and the same two existing
  `openai.computer_use` request/response skips.
- **368 independent comparisons:** 348 match Python; 20 documented MAP-10
  corrections are checked against explicit expected results, with zero
  unexpected differences. See [history-content.md](docs/history-content.md).
- The standalone JAR and its offline example compile/run from a temporary
  directory without this repository, a contract checkout or runtime dependencies.

Local native tests ran in a network namespace with loopback enabled and an
empty temporary home. Shared checks and independent probes ran without network
access; all local harness direction reports record `sandboxed: true`.

CI tests Java 21 and 25 on Linux and Java 21 on macOS. Each job runs the native
tests, standalone JAR check, unchanged pinned harness and independent probes.
It uploads test and comparison reports. The temporary Java shim registration
never modifies the contract repository.

## Repairs verified through public clients

- Direct adapters and the router now use `AuthChain`, the same selection used
  by diagnostics. The duplicate router chain was removed; its tests now target
  the shared chain rather than an implementation the client never calls.
- AWS profile/region discovery and signed requests work through the router.
  Azure token exchange/cache and GCP ADC project discovery use the injected
  transport and clock. Construction performs no credential exchange or command.
- Credential callbacks run once per request, never during construction. A null
  callback result fails without falling back or sending. Known static credential
  kinds are still validated during construction. Reusing a builder cannot retain
  another environment's resolved credentials.
- Mapped credential files and CLI paths match through directory aliases (such
  as macOS `/var` and `/private/var`), even when only the parent exists on disk.
  Regression tests reproduce this on Linux too; no platform skip is added.
- Stored-login discovery respects the supplied home/path. Codex completion
  assembles its streaming response instead of treating SSE bytes as plain JSON.
- Raw parse failures and response assembly close their sources once; early
  close cannot deliver prefetched events or manufacture a completed response.
  Post-completion cleanup errors use the warning channel and do not replace the
  answer. Ordinary failures in a custom warning handler cannot break the answer.
- SSE handles LF/CRLF/CR, an initial BOM, empty data fields and exact whitespace.
  Line limits apply while reading, not after an unbounded line has been buffered.
- Streaming body reads time out after headers too; HTTP timeouts retain their
  `TimeoutError` type. Credential subprocesses have a deadline, concurrent stdout
  and stderr drains, a 1 MiB limit on each output, and caller-controlled PATH.

## Reproduce

```sh
mvn -B package
python3 tools/check_contract.py --contract ../lm15-contract --direction all
python3 tools/differential.py --contract ../lm15-contract \
  --python-repo ../lm15-python --verify-documented-fixes
```

Without `--verify-documented-fixes`, the comparison tool intentionally reports
all 20 differences and exits nonzero. That mode is useful for inspecting drift;
the flag does not skip cases or accept arbitrary results.

## Boundaries

No real provider, paid generation, interactive login, real cloud metadata
service or live provider WebSocket was exercised. New media handling is supported
by provider documentation and offline byte checks, not live receipts. Windows
was not tested. The already stated feature limits remain, including the separate
live-session API rather than chat completion over realtime sockets and phase-2
Bedrock event-stream framing.

The injected provider transport is used for cloud exchanges and provider calls.
Borrowed OAuth stores retain their dedicated refresh implementation; this work
does not claim that injected provider transport/clock controls that refresh wire.

No Maven Central publication or release tag was made. Shared fixtures and their
pin were not changed to make the port pass.
