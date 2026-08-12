package com.nexusiq.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexusiq.ai-service")
public record AiServiceProperties(String baseUrl, String internalToken) {}
