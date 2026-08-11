package com.auditlog.service.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class RetentionRunRequest {
    @Positive(message = "retentionWindowDays must be positive")
    private Integer retentionWindowDays;
    private Boolean dryRun;
    private String approvalRef;
    @NotBlank(message = "requestedBy is required")
    private String requestedBy;
    private String approvedBy;
    private String reason;

    public Integer getRetentionWindowDays() {
        return retentionWindowDays;
    }

    public void setRetentionWindowDays(Integer retentionWindowDays) {
        this.retentionWindowDays = retentionWindowDays;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getApprovalRef() {
        return approvalRef;
    }

    public void setApprovalRef(String approvalRef) {
        this.approvalRef = approvalRef;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
