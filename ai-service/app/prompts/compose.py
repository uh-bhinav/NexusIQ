"""Composes an agent's system prompt from its template plus the standing
shared fragments (docs/AI/PROMPTS.md) — one place this happens so the
injection clause can never drift between agents."""

from functools import lru_cache
from pathlib import Path

_PROMPTS_DIR = Path(__file__).resolve().parent
_SHARED_DIR = _PROMPTS_DIR / "_shared"

_FRAGMENT_PLACEHOLDERS = {
    "{{ _shared/injection_defence.md }}": "injection_defence.md",
    "{{ _shared/honesty.md }}": "honesty.md",
    "{{ _shared/evidence_citation.md }}": "evidence_citation.md",
}


@lru_cache
def compose_prompt(template_filename: str) -> str:
    """`template_filename` is a file under app/prompts/, e.g. "intent_v1.md".
    Cached because prompt files never change at runtime, only across a
    deploy. Only substitutes the placeholders actually present in a given
    template — an agent that doesn't use evidence_citation.md (e.g. intent)
    simply never has that placeholder to replace."""
    template = (_PROMPTS_DIR / template_filename).read_text()
    for placeholder, fragment_filename in _FRAGMENT_PLACEHOLDERS.items():
        if placeholder in template:
            template = template.replace(placeholder, (_SHARED_DIR / fragment_filename).read_text())
    return template
