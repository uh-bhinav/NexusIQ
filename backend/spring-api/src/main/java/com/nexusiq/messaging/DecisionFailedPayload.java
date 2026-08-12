package com.nexusiq.messaging;

import java.util.UUID;

public record DecisionFailedPayload(UUID decisionId, String reason) {}
