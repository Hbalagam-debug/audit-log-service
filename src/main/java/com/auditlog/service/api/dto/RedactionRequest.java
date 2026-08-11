package com.auditlog.service.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class RedactionRequest {
    @NotEmpty(message = "jsonPointers is required")
    private List<String> jsonPointers;

    @NotBlank(message = "reasonCode is required")
    private String reasonCode;

    private String approvalRef;

    @NotBlank(message = "requestedBy is required")
    private String requestedBy;

    private String approvedBy;

    public List<String> getJsonPointers() {
        return jsonPointers;
    }

    public void setJsonPointers(List<String> jsonPointers) {
        this.jsonPointers = jsonPointers;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
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
}
