package com.nexusiq.streaming;

import com.nexusiq.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** `GET .../stream` (docs/API/API_DESIGN.md "SSE") and the stream-token
 * endpoint a browser needs first, since native {@code EventSource} can't
 * set an Authorization header (see JwtAuthenticationFilter's Javadoc). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/decisions/{decisionId}")
public class DecisionStreamController {

    private final DecisionStreamService streamService;

    public DecisionStreamController(DecisionStreamService streamService) {
        this.streamService = streamService;
    }

    @PostMapping("/stream-token")
    public Map<String, String> issueStreamToken(
            @PathVariable UUID workspaceId, @PathVariable UUID decisionId) {
        String token = streamService.issueStreamToken(workspaceId, decisionId, CurrentUser.id());
        return Map.of("token", token);
    }

    @GetMapping(path = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable UUID workspaceId, @PathVariable UUID decisionId) {
        return streamService.openStream(workspaceId, decisionId, CurrentUser.id());
    }
}
