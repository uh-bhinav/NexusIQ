"""Heuristic prompt-injection scan at ingestion (.claude/rules/security.md
defence #3). Retrieved documents are untrusted, hostile input — a chunk
matching one of these patterns gets flagged (`is_flagged`,
`flag_reason='PROMPT_INJECTION_SUSPECTED'`) so it's visible in the UI and so
later phases' agents can treat it specially. This is a heuristic, not a
guarantee: defence in depth (system prompt framing, structured output) still
applies at generation time (Phase 4+).
"""

import re

FLAG_REASON_PROMPT_INJECTION = "PROMPT_INJECTION_SUSPECTED"

_INJECTION_PATTERNS = [
    re.compile(r"ignore (all |any )?(the )?(above|previous|prior|earlier) instructions", re.I),
    re.compile(r"disregard (all |any )?(the )?(above|previous|prior|earlier) instructions", re.I),
    re.compile(r"new instructions\s*:", re.I),
    re.compile(r"reveal (your|the) (system prompt|instructions|prompt)", re.I),
    re.compile(r"forget (your|the) (system prompt|instructions)", re.I),
    re.compile(r"override (your|the) (instructions|programming|guidelines)", re.I),
    re.compile(r"pretend (you are|to be) (an?|the)", re.I),
    re.compile(r"you are now (an? )?(ai|assistant|chatbot) (acting|operating|working) as", re.I),
    re.compile(r"\bthis is (an? )?(system|admin|developer) (message|override|command)\b", re.I),
]


def scan_for_injection(text: str) -> str | None:
    """Returns the flag reason if `text` looks like an injection attempt, else None."""
    for pattern in _INJECTION_PATTERNS:
        if pattern.search(text):
            return FLAG_REASON_PROMPT_INJECTION
    return None
