package com.nexusiq.messaging;

import java.util.UUID;

public record DocumentFailedPayload(UUID documentId, String reason) {}
