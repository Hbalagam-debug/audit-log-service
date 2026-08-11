package com.auditlog.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "audit.redaction")
@Validated
public class RedactionProperties {
    private boolean requireApproval = true;
    private String maskValue = "[REDACTED]";
    private String allowList = "/ipAddress,/deviceId,/device/id,/accountNumber,/personalIdentifier";

    public boolean isRequireApproval() {
        return requireApproval;
    }

    public void setRequireApproval(boolean requireApproval) {
        this.requireApproval = requireApproval;
    }

    public String getMaskValue() {
        return maskValue;
    }

    public void setMaskValue(String maskValue) {
        this.maskValue = maskValue;
    }

    public String getAllowList() {
        return allowList;
    }

    public void setAllowList(String allowList) {
        this.allowList = allowList;
    }
}
