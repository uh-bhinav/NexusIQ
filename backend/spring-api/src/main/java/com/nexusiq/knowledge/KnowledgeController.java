package com.nexusiq.knowledge;

import com.nexusiq.knowledge.dto.KnowledgeSearchResponse;
import com.nexusiq.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/search")
    public KnowledgeSearchResponse search(
            @PathVariable UUID workspaceId,
            @RequestParam String q,
            @RequestParam(name = "documentTypes", required = false) List<String> documentTypes) {
        return knowledgeService.search(workspaceId, CurrentUser.id(), q, documentTypes);
    }
}
