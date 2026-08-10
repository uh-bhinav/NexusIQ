package com.nexusiq.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexusiq.jwt")
public record JwtProperties(String secret, long accessTtlSeconds, long refreshTtlSeconds) {}
