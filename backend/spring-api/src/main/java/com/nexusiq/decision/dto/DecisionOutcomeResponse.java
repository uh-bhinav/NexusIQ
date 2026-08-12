package com.nexusiq.decision.dto;

import com.nexusiq.decision.entity.FinalStatus;
import com.nexusiq.decision.entity.RecommendationType;
import com.nexusiq.decision.entity.RiskLevel;
import java.math.BigDecimal;
import java.util.List;

public record DecisionOutcomeResponse(
        RecommendationType recommendation,
        String reasoningSummary,
        BigDecimal confidence,
        RiskLevel riskLevel,
        BigDecimal evidenceCoverage,
        Boolean validationPassed,
        boolean requiresHumanApproval,
        List<String> escalationReasons,
        List<String> requiredActions,
        List<String> conditions,
        List<String> unresolvedQuestions,
        FinalStatus finalStatus) {}
