package com.auditlog.service.api.dto;

public class ComplianceBundleRequest {
    private String accountId;
    private String resourceId;
    private String from;
    private String to;
    private String approvalRef;
    private String actorId;
    private String action;
    private String outcome;
    private boolean includeArchived;
    private String reasonCode;

    public ComplianceBundleRequest() {}

    public String accountId()      { return accountId; }
    public String resourceId()     { return resourceId; }
    public String from()           { return from; }
    public String to()             { return to; }
    public String approvalRef()    { return approvalRef; }
    public String actorId()        { return actorId; }
    public String action()         { return action; }
    public String outcome()        { return outcome; }
    public boolean includeArchived() { return includeArchived; }
    public String reasonCode()     { return reasonCode; }

    public void setAccountId(String accountId)         { this.accountId = accountId; }
    public void setResourceId(String resourceId)       { this.resourceId = resourceId; }
    public void setFrom(String from)                   { this.from = from; }
    public void setTo(String to)                       { this.to = to; }
    public void setApprovalRef(String approvalRef)     { this.approvalRef = approvalRef; }
    public void setActorId(String actorId)             { this.actorId = actorId; }
    public void setAction(String action)               { this.action = action; }
    public void setOutcome(String outcome)             { this.outcome = outcome; }
    public void setIncludeArchived(boolean includeArchived) { this.includeArchived = includeArchived; }
    public void setReasonCode(String reasonCode)       { this.reasonCode = reasonCode; }
}
