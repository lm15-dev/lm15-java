#!/usr/bin/env python3
"""Differential probe: the same canonical requests through the Python reference
shim and the Java shim, compared strictly (playbooks/port.md § Reviewing a
port, step 5: probe outside the corpus).

Usage (from the lm15-contract checkout, both shims registered in
harness/shims.json):

    python3 ../lm15-java/tools/differential.py [--report out.json]

Every difference — and every place where one side refuses and the other
answers — is a finding. Nothing here touches the network.
"""

from __future__ import annotations

import argparse
import copy
import json
import subprocess
import sys
from pathlib import Path

CONTRACT = Path(__file__).resolve().parents[2] / "lm15-contract"
sys.path.insert(0, str(CONTRACT / "harness"))
import check  # noqa: E402  (the harness comparator; stdlib only)

PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="

def text(t):
    return {"type": "text", "text": t}

def user(*parts):
    return {"role": "user", "parts": list(parts)}

def assistant(*parts):
    return {"role": "assistant", "parts": list(parts)}

def tool_msg(*parts):
    return {"role": "tool", "parts": list(parts)}

TOOL = {"type": "function", "name": "get_weather", "description": "Weather", "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}

# Hand-built requests the corpus does not contain: combinations of fields and
# part kinds across messages (media inside a tool result, a config knob on a
# dialect with no slot, a citation on replay, a path-addressed part, ...).
PROBES = {
    "media_in_tool_result": {
        "model": "m", "messages": [user(text("look")), assistant({"type": "tool_call", "id": "c1", "name": "get_weather", "input": {"city": "x"}}),
                                   tool_msg({"type": "tool_result", "id": "c1", "content": [text("cloudy"), {"type": "image", "media_type": "image/png", "data": PNG}]})],
        "tools": [TOOL],
    },
    "document_in_tool_result": {
        "model": "m", "messages": [user(text("look")), assistant({"type": "tool_call", "id": "c1", "name": "get_weather", "input": {}}),
                                   tool_msg({"type": "tool_result", "id": "c1", "content": [{"type": "document", "media_type": "application/pdf", "data": PNG}], "is_error": True})],
        "tools": [TOOL],
    },
    "citation_on_replay": {
        "model": "m", "messages": [user(text("cite")), assistant(text("Paris"), {"type": "citation", "url": "https://example.com", "title": "Ex"}), user(text("more"))],
    },
    "thinking_replay_no_state": {
        "model": "m", "messages": [user(text("q")), assistant({"type": "thinking", "text": "hmm"}, text("a")), user(text("again"))],
    },
    "refusal_replay": {
        "model": "m", "messages": [user(text("q")), assistant({"type": "refusal", "text": "no"}), user(text("why"))],
    },
    "developer_mid_conversation": {
        "model": "m", "messages": [user(text("hi")), assistant(text("hello")), {"role": "developer", "parts": [text("be brief")]}, user(text("go"))],
        "system": "sys",
    },
    "system_parts": {
        "model": "m", "messages": [user(text("hi"))], "system": [text("a"), text("b")],
    },
    "every_knob": {
        "model": "m", "messages": [user(text("hi"))], "tools": [TOOL],
        "config": {"max_tokens": 10, "temperature": 0.5, "top_p": 0.9, "top_k": 3, "stop": ["x", "y"], "service_tier": "default", "user_id": "u1",
                   "store": False, "logprobs": 2, "tool_choice": {"mode": "auto", "parallel": False}, "cache": {"mode": "auto", "prefix": "stable"}},
    },
    "reasoning_max_budget": {
        "model": "m", "messages": [user(text("hi"))], "config": {"reasoning": {"effort": "max", "thinking_budget": 4096, "summary": "detailed"}},
    },
    "reasoning_off": {
        "model": "m", "messages": [user(text("hi"))], "config": {"reasoning": {"effort": "off"}},
    },
    "json_schema_named": {
        "model": "m", "messages": [user(text("hi"))],
        "config": {"response_format": {"type": "json_schema", "schema": {"type": "object", "properties": {"a": {"type": "integer"}}, "required": ["a"], "additionalProperties": False}, "name": "shape", "strict": True}},
    },
    "json_object": {
        "model": "m", "messages": [user(text("hi"))], "config": {"response_format": {"type": "json_object"}},
    },
    "tool_choice_forced_single": {
        "model": "m", "messages": [user(text("hi"))], "tools": [TOOL, {"type": "builtin", "name": "web_search"}],
        "config": {"tool_choice": {"mode": "required", "allowed": ["get_weather"]}},
    },
    "tool_choice_builtin_allowed": {
        "model": "m", "messages": [user(text("hi"))], "tools": [TOOL, {"type": "builtin", "name": "web_search"}],
        "config": {"tool_choice": {"mode": "required", "allowed": ["web_search"]}},
    },
    "tool_choice_none": {
        "model": "m", "messages": [user(text("hi"))], "tools": [TOOL], "config": {"tool_choice": {"mode": "none"}},
    },
    "media_url_and_file_id": {
        "model": "m", "messages": [user(text("see"), {"type": "image", "media_type": "image/jpeg", "url": "https://x/y.jpg", "detail": "low"},
                                        {"type": "image", "media_type": "image/png", "file_id": "file_1"},
                                        {"type": "audio", "media_type": "audio/mpeg", "data": PNG},
                                        {"type": "video", "media_type": "video/mp4", "url": "https://x/v.mp4"},
                                        {"type": "binary", "media_type": "application/zip", "data": PNG})],
    },
    "cache_history_long": {
        "model": "m", "messages": [user(text("a")), assistant(text("b")), user(text("c"))], "system": "s",
        "config": {"cache": {"mode": "auto", "prefix": "history", "retention": "long"}},
    },
    "cache_off": {
        "model": "m", "messages": [user(text("a"))], "config": {"cache": {"mode": "off"}},
    },
    "cache_key_and_index": {
        "model": "m", "messages": [user(text("a")), assistant(text("b")), user(text("c"))],
        "config": {"cache": {"mode": "auto", "key": "k1", "prefix_until_index": 1}},
    },
    "extensions_passthrough": {
        "model": "m", "messages": [user(text("a"))], "config": {"extensions": {"seed": 1, "nested": {"x": 1.0, "e": {}}, "empty": ""}},
    },
    "multi_tool_results_one_message": {
        "model": "m", "messages": [user(text("go")), assistant({"type": "tool_call", "id": "a", "name": "get_weather", "input": {"city": "x"}}, {"type": "tool_call", "id": "b", "name": "get_weather", "input": {"city": "y"}}),
                                   tool_msg({"type": "tool_result", "id": "a", "content": [text("1")], "name": "get_weather"}, {"type": "tool_result", "id": "b", "content": [text("")]})],
        "tools": [TOOL],
    },
    "continuation_states": {
        "model": "m", "messages": [user(text("q")), assistant({"type": "thinking", "text": "", "continuation": [{"provider": "anthropic", "kind": "redacted_thinking", "data": {"data": "blob"}}, {"provider": "openai", "kind": "reasoning_item", "data": {"id": "rs_1", "encrypted_content": "enc"}}]}, text("a")), user(text("more"))],
    },
    "empty_text_parts": {
        "model": "m", "messages": [user(text("")), assistant(text("")), user(text("x"))],
    },
}

PROVIDERS = {
    "openai": {"model": "gpt-5.4"},
    "openai_chat": {"model": "gpt-4.1-mini"},
    "anthropic": {"model": "claude-sonnet-4-5"},
    "gemini": {"model": "gemini-2.5-flash"},
    "groq": {"model": "openai/gpt-oss-20b"},
    "xai": {"model": "grok-4"},
    "deepseek": {"model": "deepseek-chat"},
    "claude-code": {"model": "claude-sonnet-4-5"},
}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--python", default="python")
    ap.add_argument("--java", default="java")
    ap.add_argument("--report")
    args = ap.parse_args(argv)
    ref = check.load_shim(args.python)
    port = check.load_shim(args.java)
    findings = []
    total = 0
    try:
        for provider, spec in PROVIDERS.items():
            for name, probe in PROBES.items():
                req = copy.deepcopy(probe)
                req["model"] = spec["model"]
                for stream in (False, True):
                    total += 1
                    fields = {"provider": provider, "canonical_request": req, "stream": stream, "api_key": check.API_KEY}
                    a = ref.call("build_request", **fields)
                    b = port.call("build_request", **fields)
                    case_id = f"{provider}.{name}{'.stream' if stream else ''}"
                    if a.get("ok") != b.get("ok"):
                        findings.append({"case": case_id, "note": "one side refused", "python": a, "java": b})
                        continue
                    if not a.get("ok"):
                        ea, eb = a["error"], b["error"]
                        if (ea.get("type"), ea.get("code")) != (eb.get("type"), eb.get("code")):
                            findings.append({"case": case_id, "note": "different refusal", "python": ea, "java": eb})
                        continue
                    diff = check.first_difference(a["result"], b["result"])
                    if diff is not None:
                        findings.append({"case": case_id, "note": "different wire", "diff": diff.to_dict()})
    finally:
        ref.close()
        port.close()
    print(f"{total} comparisons, {len(findings)} findings")
    for f in findings:
        print(json.dumps(f)[:400])
    if args.report:
        Path(args.report).write_text(json.dumps({"total": total, "findings": findings}, indent=2))
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
