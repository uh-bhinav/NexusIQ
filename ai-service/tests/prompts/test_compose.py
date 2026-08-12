"""No test exercised app/prompts/compose.py before — the single place that
splices the injection-defense fragment into every agent's system prompt
(.claude/rules/security.md defense #1/#2, .claude/rules/ai-service.md's
mandatory injection-defence clause). A silent placeholder-drift here would
strip that defense from an agent with no other signal."""

from pathlib import Path

from app.prompts.compose import compose_prompt

_PROMPTS_DIR = Path(__file__).resolve().parents[2] / "app" / "prompts"
_SHARED_DIR = _PROMPTS_DIR / "_shared"

# Every real agent template currently in app/prompts/ — a hardcoded list
# (not a glob) so adding a new template file without adding it here fails
# this test loudly rather than silently skipping coverage.
_AGENT_TEMPLATES = [
    "intent_v1.md",
    "context_planner_v1.md",
    "policy_analyst_v1.md",
    "risk_analyzer_v1.md",
    "decision_v1.md",
    "validator_v1.md",
]


def test_everyAgentTemplate_actuallyExistsOnDisk():
    # Guards the hardcoded list above against drifting from reality in
    # either direction.
    on_disk = {p.name for p in _PROMPTS_DIR.glob("*.md")}
    assert on_disk == set(_AGENT_TEMPLATES)


def test_everyAgentTemplate_composesTheRealInjectionDefenceTextVerbatim():
    # The single most security-critical assertion in this file: every agent
    # that references the injection-defence placeholder must end up with
    # the actual, current fragment content in its composed prompt — not a
    # stale copy, not a silently-dropped substitution.
    injection_defence_text = (_SHARED_DIR / "injection_defence.md").read_text()

    for template_name in _AGENT_TEMPLATES:
        raw = (_PROMPTS_DIR / template_name).read_text()
        if "{{ _shared/injection_defence.md }}" not in raw:
            continue
        composed = compose_prompt(template_name)
        assert injection_defence_text in composed, (
            f"{template_name} references the injection-defence placeholder but the "
            "composed prompt doesn't contain the real fragment text"
        )
        assert "{{ _shared/injection_defence.md }}" not in composed


def test_allSixAgentTemplates_referenceInjectionDefence():
    # Every one of the six real agents currently does. If a future agent
    # template is added without it, this is the test that should catch the
    # omission rather than assuming compose_prompt alone enforces it (it
    # doesn't — it only substitutes what's present).
    for template_name in _AGENT_TEMPLATES:
        raw = (_PROMPTS_DIR / template_name).read_text()
        assert "{{ _shared/injection_defence.md }}" in raw, (
            f"{template_name} has no injection-defence placeholder at all"
        )


def test_composePrompt_onlySubstitutesPlaceholdersActuallyPresent():
    # intent_v1.md doesn't reference evidence_citation.md (it never cites
    # evidence) — composing it must not leave a stray unsubstituted
    # placeholder, and must not accidentally pull in citation guidance it
    # never asked for.
    composed = compose_prompt("intent_v1.md")
    evidence_citation_text = (_SHARED_DIR / "evidence_citation.md").read_text()

    assert "{{ _shared/evidence_citation.md }}" not in composed
    assert evidence_citation_text not in composed


def test_composePrompt_substitutesEvidenceCitationWhereReferenced():
    composed = compose_prompt("policy_analyst_v1.md")
    evidence_citation_text = (_SHARED_DIR / "evidence_citation.md").read_text()

    assert evidence_citation_text in composed
    assert "{{ _shared/evidence_citation.md }}" not in composed


def test_composePrompt_isCached_sameFileNotReReadOnSecondCall(monkeypatch):
    # compose_prompt is process-wide @lru_cache'd (module import survives
    # across tests), so other tests calling it for this same template
    # earlier in the run would otherwise make this test pass by accident —
    # clear the cache first to actually observe a cold call.
    compose_prompt.cache_clear()
    read_count = 0
    original_read_text = Path.read_text

    def counting_read_text(self, *args, **kwargs):
        nonlocal read_count
        if self.name == "validator_v1.md":
            read_count += 1
        return original_read_text(self, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", counting_read_text)

    compose_prompt("validator_v1.md")
    compose_prompt("validator_v1.md")

    assert read_count == 1
    compose_prompt.cache_clear()
