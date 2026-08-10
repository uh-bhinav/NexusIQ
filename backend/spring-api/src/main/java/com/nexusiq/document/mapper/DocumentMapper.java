package com.nexusiq.document.mapper;

import com.nexusiq.document.dto.DocumentResponse;
import com.nexusiq.document.entity.Document;
import org.springframework.stereotype.Component;

@Component
public class DocumentMapper {

    public DocumentResponse toResponse(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getWorkspaceId(),
                d.getName(),
                d.getDocumentType(),
                d.getVersion(),
                d.isCurrent(),
                d.getStatus(),
                d.getFailureReason(),
                d.getChunkCount(),
                d.getUploadedBy(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }
}
