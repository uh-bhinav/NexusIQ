# Security Policy (SP-102) — Version 2

**Status:** Current. Supersedes Version 1 (security-policy-v1.md) in full.
Where this version conflicts with Version 1, this document controls.
**Effective:** 2024-06-01
**Owner:** Security & Compliance

## Change summary since Version 1

Section 1 (Data Handling and Residency) is tightened from a general
"adequate safeguards" standard to an explicit EU/EEA data-center requirement
for in-scope EU/EEA customer data, in response to updated regulatory
guidance. This is a deliberate, substantive policy change, not a
clarification — the two versions genuinely disagree on where EU/EEA data
may be processed, and Version 2's stricter requirement is the one that
applies to any decision made on or after the effective date above.

## Section 1: Data Handling and Residency

All customer data processed on behalf of in-scope EU/EEA customers must be
stored and processed exclusively within EU/EEA data centers. Processing
outside the EU/EEA for these customers is not permitted under this policy,
regardless of the safeguards in place at the external location.

Customer data for non-EU/EEA customers may continue to be processed in any
region meeting the adequacy standard from Version 1, Section 1.

## Section 2: Encryption

All customer data must be encrypted at rest using AES-256 or equivalent, and
in transit using TLS 1.3 or higher (raised from TLS 1.2 in Version 1).

## Section 3: Incident Response

Vendors must notify NexusIQ of any confirmed data security incident within
4 hours of discovery (tightened from 72 hours in Version 1), consistent with
GDPR Article 33's 72-hour regulator-notification deadline leaving adequate
internal response time.

## Section 4: Certification

Vendors must hold a current SOC 2 Type II certification, renewed annually
(tightened from 18 months in Version 1).
