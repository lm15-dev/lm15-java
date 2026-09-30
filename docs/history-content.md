# Message history: native content or an explicit refusal

MAP-10 in the pinned contract requires every part in every message to reach a
native provider block or fail before sending. Matching Python is insufficient:
its pinned implementation drops some media and citation details.

Java now follows these rules:

- **Responses:** assistant images, documents and binary files use the documented
  `EasyInputMessage` shape. Text in the same message becomes `input_text`; the
  images/files remain native `input_image` / `input_file` blocks in order.
  Ordinary text-only history keeps its existing `output_text` encoding.
- **Responses refusals:** assistant audio/video, and a refusal mixed with media
  in one assistant input message, raise `UnsupportedFeatureError`. The declared
  input-content list has no native block for those combinations. No role is
  changed and no media is converted to a caption or prose containing base64.
- **Chat Completions:** assistant media raises before credential callbacks or
  transport. Assistant content supports text/refusal, not arbitrary media. The
  separate `audio.id` field identifies a previous provider response and must
  not be fabricated from a file ID or URL.
- **Anthropic:** images/documents retain their native blocks; audio/video/binary
  parts raise instead of becoming empty text, for all message roles.
- **Gemini:** existing native media handling remains unchanged.
- **Citations:** all four dialects replay the title, URL and quote through the
  existing text-bearing-part renderer, without inventing annotation offsets.
- **Uploaded images:** their `detail` setting survives alongside `file_id`.

These mappings do not claim that every provider/model supports each format;
existing provider capability checks still apply, and unsupported models can
fail explicitly on the server. No live-provider receipt is claimed here.

## Evidence

- Contract `cfed00771dffef2a218b5549151950bf5060ca06`,
  `docs/mapping-rules.md`, MAP-10 rules 1–5; `playbooks/port.md`, rule 4.
- [OpenAI Responses create reference](https://developers.openai.com/api/reference/resources/responses/methods/create):
  `EasyInputMessage` accepts `role: assistant` and its input-content list declares
  text, image and file blocks.
- [OpenAI Chat create reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create):
  `ChatCompletionAssistantMessageParam.content` contains text/refusal blocks.
- Frozen local copies: `curl-fixtures` commit
  `35fa9a71a6f0beddd1236c4ccdb54d42f26d588b`, under
  `api-references/openai/pages/responses--create.md` and `chat--create.md`.

`HistoryContentTest` verifies public request builders, including ordering,
unchanged caller input and refusal before credential callbacks.

## Independent comparisons

The 368 existing probes now find **20 differences from Python**: 16 citation
replays and four Anthropic/Claude Code requests containing unsupported media.
These are corrections, not silently ignored test failures.

`tools/differential.py` remains strict by default: it reports these differences
and exits nonzero. `--verify-documented-fixes` checks their exact expected
outcomes under MAP-10 and records every reference difference separately. For
citation cases, the rest of each request must still equal Python, and the
reference's known lossy block must match exactly before the correction is
applied. For unsupported media, the exact refusal class and code are required.
The mode fails missing corrections, wrong expected bytes or any other difference.
It requires the pinned, clean Python revision and is the mode CI uses. No probe,
assertion, contract fixture or skip was removed to make the checks pass.

**2026-09-30: the Claude Code release.** This port now claims Claude Code 2.1.285
(`user-agent: claude-cli/2.1.285`), because Anthropic refuses newer models on the
2.1.170 the pinned reference still sends (lm15-contract
`changes/2026-09-30-claude-code-client-version.md`, live receipts
`receipts/2026-09-30-claude-code/`). The same mode checks it on every Claude Code
comparison where the reference builds a request (a request both refuse has no
header): the reference must send exactly `claude-cli/2.1.170`, this port exactly
`claude-cli/2.1.285`, and every other byte must match. The `client_version`
setting and the other changes of that contract entry arrive with this port's
catch-up to the current contract.
