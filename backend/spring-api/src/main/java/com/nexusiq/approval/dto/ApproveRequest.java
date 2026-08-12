package com.nexusiq.approval.dto;

import jakarta.validation.constraints.Size;

public record ApproveRequest(@Size(max = 2000) String notes) {}
