package com.nexusiq.decision.entity;

/** Null for RISK-category findings — only POLICY findings carry a compliance status. */
public enum FindingStatus {
    SATISFIED,
    PARTIALLY_SATISFIED,
    VIOLATED,
    UNKNOWN
}
