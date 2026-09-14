package com.costoptimizer.model;

import java.util.Map;

public class Finding {
    private String resourceId;
    private String resourceType;
    private double estimatedMonthlySavings;
    private String detectedAt;
    private String status;
    private Map<String, String> details;

    public Finding() {}

    public Finding(String resourceId, String resourceType, double estimatedMonthlySavings, String detectedAt, String status, Map<String, String> details) {
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.estimatedMonthlySavings = estimatedMonthlySavings;
        this.detectedAt = detectedAt;
        this.status = status;
        this.details = details;
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public double getEstimatedMonthlySavings() { return estimatedMonthlySavings; }
    public void setEstimatedMonthlySavings(double estimatedMonthlySavings) { this.estimatedMonthlySavings = estimatedMonthlySavings; }

    public String getDetectedAt() { return detectedAt; }
    public void setDetectedAt(String detectedAt) { this.detectedAt = detectedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, String> getDetails() { return details; }
    public void setDetails(Map<String, String> details) { this.details = details; }

    @Override
    public String toString() {
        return "Finding{" +
                "resourceId='" + resourceId + '\'' +
                ", resourceType='" + resourceType + '\'' +
                ", estimatedMonthlySavings=" + estimatedMonthlySavings +
                ", detectedAt='" + detectedAt + '\'' +
                ", status='" + status + '\'' +
                ", details=" + details +
                '}';
    }
}
