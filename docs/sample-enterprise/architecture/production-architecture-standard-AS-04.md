# Production Architecture Standard (AS-04)

**Effective:** 2023-11-01
**Owner:** Engineering Architecture

## Section 1: Availability

Any vendor system integrated into a production data flow must document an
uptime commitment of at least 99.9% measured monthly, with a published
incident-communication process.

## Section 2: Disaster Recovery

Vendors must document a Recovery Time Objective (RTO) and Recovery Point
Objective (RPO) for any system holding NexusIQ production data. An RTO
without a corresponding RPO (or vice versa) is treated as an incomplete
disclosure, not as evidence of resilience.

## Section 3: Monitoring and Alerting

Vendors must provide either direct integration with NexusIQ's monitoring
stack or a status page with programmatic (API or RSS) access, so that an
outage on the vendor side is independently observable rather than relying
solely on the vendor's own notification.

## Section 4: Network Isolation

Any vendor integration accepting inbound traffic from NexusIQ systems must
support IP allowlisting or an equivalent network-level restriction. Vendors
that only support open internet exposure require an Architecture Review
Board exception before approval.
