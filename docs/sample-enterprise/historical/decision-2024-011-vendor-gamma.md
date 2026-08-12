# Historical Decision Record: 2024-011 — Vendor Gamma Production Approval

**Decision:** REJECTED
**Date:** 2024-04-12
**Requested environment:** Production (EU/EEA customer base)

## Question

Should Vendor Gamma be approved for production use serving NexusIQ's
EU/EEA customers?

## Findings

- **Data residency (DR-11): VIOLATED.** Vendor Gamma's data processing
  addendum disclosed primary data processing in a US data center with no
  EU/EEA presence, and no Standard Contractual Clauses or adequacy
  mechanism was on file at the time of review.
- **Security certification (SP-102 v1, then current): SATISFIED.** Vendor
  Gamma held a current SOC 2 Type II report.
- **Availability (AS-04): PARTIALLY_SATISFIED.** Vendor Gamma disclosed an
  RTO of 8 hours but did not disclose a corresponding RPO.

## Outcome

Rejected on the data residency violation alone — the Vendor Risk Committee
determined this was not remediable within the requested timeline, since
Vendor Gamma had no EU/EEA infrastructure and no transfer mechanism was
in progress. The RTO/RPO gap was noted but was not the deciding factor.

## Required actions for resubmission

1. Establish EU/EEA data processing capability, or execute Standard
   Contractual Clauses with a documented Transfer Impact Assessment.
2. Disclose a complete RTO/RPO pair for all production systems.

Vendor Gamma has not resubmitted as of this record's filing date.
