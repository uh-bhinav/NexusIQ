package com.nexusiq.messaging;

import java.util.UUID;

/** Payload for {@code decision.requested}. Mirrors ai-service's
 * DecisionRequestedPayload (app/messaging/envelope.py). */
public record DecisionRequestedPayload(UUID decisionId, String question) {}
