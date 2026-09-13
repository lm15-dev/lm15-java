#!/usr/bin/env python3
"""Differential probe: the same canonical requests through the Python reference
shim and the Java shim, compared strictly (playbooks/port.md § Reviewing a
port, step 5: probe outside the corpus).

Usage (after building the Java package):

    python3 tools/differential.py --contract ../lm15-contract --python-repo ../lm15-python [--report out.json]

By default every difference is a finding. --verify-documented-fixes additionally
checks the exact MAP-10 corrections documented in docs/history-content.md against
the pinned reference; it still records every reference difference and fails any
unexpected byte or missing correction. Nothing here touches the network.
"""

from __future__ import annotations

import argparse
import copy
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

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


REFERENCE_PIN = "3bbbd3ee1bab40a1a61cc74db120c4a8eb7eb22b"


def comparable(reply):
    if reply.get("ok"):
        return {"ok": True, "result": reply["result"]}
    error = reply["error"]
    return {"ok": False, "error": {"type": error.get("type"), "code": error.get("code")}}


def documented_expected(provider, name, reference):
    """Narrow, exact expectations for known reference defects, never an ignore list."""
    expected = copy.deepcopy(reference)
    citation = "Ex — https://example.com"
    if name == "citation_on_replay":
        assert expected["ok"], "reference citation behavior changed: re-review the correction"
        body = expected["result"]["body"]
        if provider == "openai":
            row = body["input"][1]
            assert row == {"role": "assistant", "content": [{"type": "output_text", "text": "Paris"}]}
            row["content"].append({"type": "output_text", "text": citation})
        elif provider in ("anthropic", "claude-code"):
            row = body["messages"][1]
            assert row == {"role": "assistant", "content": [{"type": "text", "text": "Paris"}, {"type": "text", "text": ""}]}
            row["content"][1]["text"] = citation
        elif provider == "gemini":
            row = body["contents"][1]
            assert row == {"role": "model", "parts": [{"text": "Paris"}, {"text": ""}]}
            row["parts"][1]["text"] = citation
        else:
            row = body["messages"][1]
            old_row = {"role": "assistant", "content": "Paris"}
            if provider == "deepseek":
                old_row["reasoning_content"] = ""
            assert row == old_row
            row["content"] = "Paris\n" + citation
        return expected, "MAP-10: retain citation title and URL"
    if name == "media_url_and_file_id" and provider in ("anthropic", "claude-code"):
        assert expected["ok"]
        assert expected["result"]["body"]["messages"] == [{"role": "user", "content": [
            {"type": "text", "text": "see"},
            {"type": "image", "source": {"type": "url", "url": "https://x/y.jpg"}},
            {"type": "image", "source": {"type": "file", "file_id": "file_1"}},
            {"type": "text", "text": ""}, {"type": "text", "text": ""}, {"type": "text", "text": ""}]}]
        return {"ok": False, "error": {"type": "UnsupportedFeatureError", "code": "unsupported_feature"}}, "MAP-10: refuse media with no Anthropic block"
    return expected, None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--python", default=sys.executable, help="Python executable")
    ap.add_argument("--java", default="java", help="Java executable")
    ap.add_argument("--contract", type=Path, default=ROOT.parent / "lm15-contract")
    ap.add_argument("--python-repo", type=Path, default=ROOT.parent / "lm15-python")
    ap.add_argument("--report", type=Path, default=ROOT / "harness-reports/differential.json")
    ap.add_argument("--verify-documented-fixes", action="store_true")
    args = ap.parse_args(argv)
    contract = args.contract.resolve()
    pin = (ROOT / "CONTRACT_PIN").read_text().strip()
    if subprocess.check_output(["git", "-C", str(contract), "rev-parse", "HEAD"], text=True).strip() != pin:
        raise SystemExit("Contract revision does not match CONTRACT_PIN")
    if subprocess.check_output(["git", "-C", str(contract), "status", "--porcelain"], text=True):
        raise SystemExit("Contract checkout must be clean")
    reference_revision = subprocess.check_output(["git", "-C", str(args.python_repo), "rev-parse", "HEAD"], text=True).strip()
    if args.verify_documented_fixes and reference_revision != REFERENCE_PIN:
        raise SystemExit("Re-review documented corrections before changing the Python reference pin")
    if subprocess.check_output(["git", "-C", str(args.python_repo), "status", "--porcelain"], text=True):
        raise SystemExit("Python reference checkout must be clean")
    sys.path.insert(0, str(contract / "harness"))
    import check
    ref = check.Shim("python", [args.python, "-m", "lm15.vet"], args.python_repo.resolve())
    port = check.Shim("java", [args.java, "-jar", str(ROOT / "target/lm15.jar")], ROOT)
    findings = []
    reference_differences = []
    verified_corrections = []
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
                    original, actual = comparable(a), comparable(b)
                    reference_diff = check.first_difference(original, actual)
                    if reference_diff is not None:
                        reference_differences.append({"case": case_id, "diff": reference_diff.to_dict()})
                    expected, rule = documented_expected(provider, name, original) if args.verify_documented_fixes else (original, None)
                    diff = check.first_difference(expected, actual)
                    if diff is not None:
                        findings.append({"case": case_id, "note": "unexpected result", "diff": diff.to_dict()})
                    elif rule:
                        verified_corrections.append({"case": case_id, "rule": rule})
    finally:
        ref.close()
        port.close()
    if args.verify_documented_fixes:
        required = {f"{p}.{n}{suffix}" for p in PROVIDERS for n in PROBES for suffix in ("", ".stream")
                    if n == "citation_on_replay" or (n == "media_url_and_file_id" and p in ("anthropic", "claude-code"))}
        missing = required - {item["case"] for item in verified_corrections}
        for case_id in sorted(missing):
            findings.append({"case": case_id, "note": "documented correction was not verified"})
    print(f"{total} comparisons, {len(reference_differences)} reference differences, {len(verified_corrections)} verified corrections, {len(findings)} findings")
    for f in findings:
        print(json.dumps(f)[:400])
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({"total": total, "reference_revision": reference_revision,
            "reference_differences": reference_differences, "verified_corrections": verified_corrections, "findings": findings}, indent=2))
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
