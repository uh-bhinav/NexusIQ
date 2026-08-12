package com.nexusiq.approval;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-006's threshold matrix. Config, not code — every change is a tuning decision. */
@ConfigurationProperties(prefix = "nexusiq.hitl")
public record HitlProperties(String escalateOnRisk, BigDecimal minConfidence, BigDecimal minEvidenceCoverage) {}
