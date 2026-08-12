-- CONFLICTING_EVIDENCE was missing from the recommendation enum entirely
-- (.claude/rules/ai-service.md: "Enums must include the honest options:
-- UNKNOWN, INSUFFICIENT_INFORMATION, CONFLICTING_EVIDENCE. A schema that
-- forces a binary answer is a bug.") — confirmed via
-- .claude/rules/testing.md's failure scenario #2 ("contradictory
-- documents -> conflict identified"), which had no valid value to express
-- its own outcome. V8's CHECK constraint is immutable now that it has
-- shipped, so this widens it rather than editing V8 directly
-- (.claude/rules/database.md).

ALTER TABLE decisions
    DROP CONSTRAINT decisions_recommendation_check;

ALTER TABLE decisions
    ADD CONSTRAINT decisions_recommendation_check
    CHECK (recommendation IN (
        'APPROVE', 'CONDITIONAL_APPROVAL', 'REJECT', 'INSUFFICIENT_INFORMATION',
        'CONFLICTING_EVIDENCE'
    ));
