package com.auditlog.service.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "audit.compliance-report")
public class ComplianceReportProperties {
    private int maxPageSize = 200;
    private int defaultPageSize = 50;
    private long maxUtcWindowMillis = 90 * 24 * 60 * 60 * 1000L; // 90 days

    @PostConstruct
    public void validate() {
        if (maxPageSize < 1) {
            throw new IllegalStateException("audit.compliance-report.max-page-size must be greater than zero");
        }
        if (defaultPageSize < 1 || defaultPageSize > maxPageSize) {
            throw new IllegalStateException("audit.compliance-report.default-page-size must be between 1 and max-page-size");
        }
        if (maxUtcWindowMillis < 1) {
            throw new IllegalStateException("audit.compliance-report.max-utc-window-millis must be greater than zero");
        }
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public long getMaxUtcWindowMillis() {
        return maxUtcWindowMillis;
    }

    public void setMaxUtcWindowMillis(long maxUtcWindowMillis) {
        this.maxUtcWindowMillis = maxUtcWindowMillis;
    }
}
