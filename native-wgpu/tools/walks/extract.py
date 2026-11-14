#!/usr/bin/env python3
"""Read walk packets with Claude through the API (for re-running the reading
step when DOC changes pages; the first readings were made in a Claude Code
session with the same instructions).

Usage: ANTHROPIC_API_KEY=... python3 extract.py PACKETS_DIR READINGS_DIR [g ...]
Requires: pip install anthropic

Each packet is sent with READING.md as the instructions; the answer is
checked with validate.check, and on problems Claude is asked again with the
list of problems (twice at most). Existing readings are kept unless named.
"""
import json, os, sys

import anthropic

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from validate import check  # noqa: E402

MODEL = "claude-opus-5-5"
INSTRUCTIONS = open(os.path.join(HERE, "READING.md"), encoding="utf-8").read()


def ask(client, messages):
    response = client.beta.messages.create(
        model=MODEL,
        max_tokens=16000,
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
        output_config={"effort": "high"},
        system=[{"type": "text", "text": INSTRUCTIONS, "cache_control": {"type": "ephemeral"}}],
        messages=messages,
    )
    if response.stop_reason == "refusal":
        raise RuntimeError("refused")
    text = "".join(b.text for b in response.content if b.type == "text").strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1].rsplit("```", 1)[0]
    return response, text


def read_one(client, packet):
    content = ("Read this packet and write its reading as READING.md says. Answer with the JSON "
               "only.\n\n" + json.dumps(packet, ensure_ascii=False, indent=1))
    messages = [{"role": "user", "content": content}]
    for attempt in range(3):
        response, text = ask(client, messages)
        try:
            reading = json.loads(text)
            problems = check(packet, reading)
        except json.JSONDecodeError as e:
            reading, problems = None, [f"not valid JSON: {e}"]
        if not problems:
            return reading
        messages += [{"role": "assistant", "content": response.content},
                     {"role": "user", "content": "The reading has these problems; answer with the corrected JSON only:\n- "
                      + "\n- ".join(problems)}]
    raise RuntimeError("; ".join(problems))


def main(packets, readings, only):
    client = anthropic.Anthropic()
    os.makedirs(readings, exist_ok=True)
    names = only or sorted(f[:-5] for f in os.listdir(packets) if f.endswith(".json") and not f.startswith("_"))
    failed = []
    for g in names:
        out = os.path.join(readings, g + ".json")
        if os.path.exists(out) and not only:
            continue
        packet = json.load(open(os.path.join(packets, g + ".json"), encoding="utf-8"))
        try:
            reading = read_one(client, packet)
            json.dump(reading, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
            print(f"{g}: ok")
        except (anthropic.APIStatusError, anthropic.APIConnectionError, RuntimeError) as e:
            failed.append(g)
            print(f"{g}: failed: {e}")
    print(f"done {len(names) - len(failed)}, failed {len(failed)}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
