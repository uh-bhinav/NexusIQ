package com.nexusiq.audit;

import com.nexusiq.audit.entity.AuditEvent;
import com.nexusiq.common.CorrelationIdFilter;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * The single write path into the append-only audit trail. Every security- or
 * decision-relevant action calls through here (.claude/rules/security.md).
 * Metadata never contains secrets or document contents.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(UUID workspaceId, UUID actorId, String eventType, String resourceType, UUID resourceId) {
        record(workspaceId, actorId, eventType, resourceType, resourceId, null);
    }

    public void record(
            UUID workspaceId,
            UUID actorId,
            String eventType,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata) {
        String metadataJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("Failed to serialize audit metadata for event {}; recording without it", eventType, e);
            }
        }

        UUID correlationId = safeCorrelationId();
        String ipAddress = currentIpAddress();

        AuditEvent event = new AuditEvent(
                workspaceId, actorId, eventType, resourceType, resourceId, correlationId, metadataJson, ipAddress);
        repository.save(event);
    }

    private UUID safeCorrelationId() {
        try {
            return UUID.fromString(CorrelationIdFilter.currentOrNew());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String currentIpAddress() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        String forwardedFor = attrs.getRequest().getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return attrs.getRequest().getRemoteAddr();
    }
}
