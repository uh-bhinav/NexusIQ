# Incident Report: 2024-07 — Vendor Alpha Service Disruption

**Severity:** Medium
**Date:** 2024-07-18
**Affected vendor:** Vendor Alpha
**Duration:** 2 hours 40 minutes

## Summary

Vendor Alpha's analytics ingestion API returned elevated error rates
(sustained 5xx responses above 15%) for 2 hours 40 minutes, degrading
NexusIQ's ability to receive updated vendor behavioral analytics during the
window. No customer data was lost; queued events were retried successfully
after the incident resolved.

## Timeline

- **14:02 UTC** — Elevated error rate first observed by NexusIQ monitoring.
- **14:10 UTC** — Vendor Alpha status page updated to acknowledge a
  "degraded performance" incident.
- **14:45 UTC** — Vendor Alpha identified the cause as an internal database
  failover that did not complete cleanly.
- **16:42 UTC** — Service fully restored; Vendor Alpha confirmed no data
  loss on their side.

## Notification

Vendor Alpha's status page was updated 8 minutes after NexusIQ's own
monitoring first detected the issue, and a direct incident notification
email followed at 14:52 UTC — 50 minutes after detection. This exceeds the
4-hour breach/incident notification commitment in Vendor Alpha's security
report, but this was a service availability incident, not a data security
incident, so the notification commitment (which covers confirmed data
incidents) was not formally breached.

## Follow-up

Vendor Alpha committed to a post-incident review and confirmed no customer
data was exposed or lost. No changes to Vendor Alpha's approval status were
made as a result of this incident; it is retained here as risk-assessment
context for future review cycles.
