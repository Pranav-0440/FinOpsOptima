package com.costoptimizer.service;

import com.costoptimizer.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.util.*;

public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);
    
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DatabaseService() {
        this.tableName = System.getenv().getOrDefault("TABLE_NAME", "CostSavingsFindings");
        String endpoint = System.getenv("DYNAMODB_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            logger.info("Overriding DynamoDB endpoint to: {}", endpoint);
            this.dynamoDbClient = DynamoDbClient.builder()
                    .endpointOverride(URI.create(endpoint))
                    .build();
        } else {
            this.dynamoDbClient = DynamoDbClient.builder().build();
        }
    }

    public void saveFinding(Finding finding) {
        logger.info("Saving finding to DynamoDB: {}", finding);
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("resource_id", AttributeValue.builder().s(finding.getResourceId()).build());
            item.put("resource_type", AttributeValue.builder().s(finding.getResourceType()).build());
            item.put("estimated_monthly_savings", AttributeValue.builder().n(String.valueOf(finding.getEstimatedMonthlySavings())).build());
            item.put("detected_at", AttributeValue.builder().s(finding.getDetectedAt()).build());
            item.put("status", AttributeValue.builder().s(finding.getStatus()).build());

            if (finding.getDetails() != null && !finding.getDetails().isEmpty()) {
                Map<String, AttributeValue> detailsMap = new HashMap<>();
                finding.getDetails().forEach((k, v) -> detailsMap.put(k, AttributeValue.builder().s(v).build()));
                item.put("details", AttributeValue.builder().m(detailsMap).build());
            }

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build();

            dynamoDbClient.putItem(putItemRequest);
            logger.info("Successfully saved finding: {}", finding.getResourceId());
        } catch (Exception e) {
            logger.error("Error saving finding to DynamoDB: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save finding", e);
        }
    }

    public List<Finding> getAllFindings() {
        logger.info("Scanning table {} for all findings", tableName);
        List<Finding> findings = new ArrayList<>();
        try {
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(tableName)
                    .build();

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
            for (Map<String, AttributeValue> item : scanResponse.items()) {
                findings.add(mapToFinding(item));
            }
            logger.info("Retrieved {} findings from database", findings.size());
        } catch (Exception e) {
            logger.error("Error scanning DynamoDB findings: {}", e.getMessage(), e);
        }
        return findings;
    }

    public Optional<Finding> getFinding(String resourceId, String resourceType) {
        logger.info("Getting finding: {} - {}", resourceId, resourceType);
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("resource_id", AttributeValue.builder().s(resourceId).build());
            key.put("resource_type", AttributeValue.builder().s(resourceType).build());

            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(getItemRequest);
            if (response.hasItem()) {
                return Optional.of(mapToFinding(response.item()));
            }
        } catch (Exception e) {
            logger.error("Error getting finding: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    public void updateFindingStatus(String resourceId, String resourceType, String status) {
        logger.info("Updating status of {} - {} to {}", resourceId, resourceType, status);
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("resource_id", AttributeValue.builder().s(resourceId).build());
            key.put("resource_type", AttributeValue.builder().s(resourceType).build());

            Map<String, AttributeValueUpdate> attributeUpdates = new HashMap<>();
            attributeUpdates.put("status", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().s(status).build())
                    .action(AttributeAction.PUT)
                    .build());

            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .attributeUpdates(attributeUpdates)
                    .build();

            dynamoDbClient.updateItem(updateItemRequest);
            logger.info("Successfully updated status of {} to {}", resourceId, status);
        } catch (Exception e) {
            logger.error("Error updating status for resource: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update status", e);
        }
    }

    private Finding mapToFinding(Map<String, AttributeValue> item) {
        Finding finding = new Finding();
        if (item.containsKey("resource_id")) finding.setResourceId(item.get("resource_id").s());
        if (item.containsKey("resource_type")) finding.setResourceType(item.get("resource_type").s());
        if (item.containsKey("estimated_monthly_savings")) {
            finding.setEstimatedMonthlySavings(Double.parseDouble(item.get("estimated_monthly_savings").n()));
        }
        if (item.containsKey("detected_at")) finding.setDetectedAt(item.get("detected_at").s());
        if (item.containsKey("status")) finding.setStatus(item.get("status").s());

        if (item.containsKey("details") && item.get("details").hasM()) {
            Map<String, String> details = new HashMap<>();
            item.get("details").m().forEach((k, v) -> details.put(k, v.s()));
            finding.setDetails(details);
        }
        return finding;
    }
}
