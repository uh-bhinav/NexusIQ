package com.nexusiq.messaging;

import java.util.UUID;

public record DocumentProcessedPayload(UUID documentId, int chunkCount) {}
