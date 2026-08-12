package com.nexusiq.decision.entity;

/** Null for POLICY-category findings — only RISK findings carry a severity. */
public enum FindingSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
