package com.auditlog.service.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "audit.retention")
@Validated
public class RetentionProperties {
    @Min(1)
    private int windowDays = 90;

    private boolean defaultDryRun = true;
    private boolean requireApproval = true;

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public boolean isDefaultDryRun() {
        return defaultDryRun;
    }

    public void setDefaultDryRun(boolean defaultDryRun) {
        this.defaultDryRun = defaultDryRun;
    }

    public boolean isRequireApproval() {
        return requireApproval;
    }

    public void setRequireApproval(boolean requireApproval) {
        this.requireApproval = requireApproval;
    }
}
