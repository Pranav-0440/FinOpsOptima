package com.costoptimizer.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.costoptimizer.model.Finding;
import com.costoptimizer.service.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class SummaryGeneratorHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(SummaryGeneratorHandler.class);

    private final DatabaseService databaseService = new DatabaseService();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Starting Summary Generator Lambda");

        List<Finding> allFindings = databaseService.getAllFindings();
        
        // Filter only ACTIVE findings
        List<Finding> activeFindings = allFindings.stream()
                .filter(f -> "ACTIVE".equalsIgnoreCase(f.getStatus()))
                .collect(Collectors.toList());

        double totalSavings = activeFindings.stream()
                .mapToDouble(Finding::getEstimatedMonthlySavings)
                .sum();

        Map<String, Long> countByType = activeFindings.stream()
                .collect(Collectors.groupingBy(Finding::getResourceType, Collectors.counting()));

        Map<String, Double> savingsByType = activeFindings.stream()
                .collect(Collectors.groupingBy(
                        Finding::getResourceType,
                        Collectors.summingDouble(Finding::getEstimatedMonthlySavings)
                ));

        String digest = buildDigestMessage(activeFindings, totalSavings, countByType, savingsByType);
        
        logger.info("Generated Daily Cost Digest:\n{}", digest);
        
        publishToSns(digest);

        return "Summary completed. Total savings potential: $" + String.format("%.2f", totalSavings) + "/month.";
    }

    private String buildDigestMessage(List<Finding> findings, double totalSavings, 
                                       Map<String, Long> countByType, Map<String, Double> savingsByType) {
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================\n");
        sb.append("      AWS COST OPTIMIZATION DAILY DIGEST\n");
        sb.append("==================================================\n");
        sb.append("Detected At: ").append(Instant.now().toString()).append("\n\n");
        
        sb.append("SUMMARY STATS:\n");
        sb.append(" - Total Potential Savings: $").append(String.format("%.2f", totalSavings)).append(" / month\n");
        sb.append(" - Active Waste Resources: ").append(findings.size()).append("\n\n");

        sb.append("BREAKDOWN BY RESOURCE TYPE:\n");
        for (String type : new String[]{"EC2", "EBS", "EIP", "RDS", "S3"}) {
            long count = countByType.getOrDefault(type, 0L);
            double savings = savingsByType.getOrDefault(type, 0.0);
            sb.append(String.format(" - %-4s: %d items (Potential Savings: $%.2f / month)\n", type, count, savings));
        }
        sb.append("\n");

        sb.append("TOP WASTE RESOURCES:\n");
        if (findings.isEmpty()) {
            sb.append(" - No active waste resources found. Good job!\n");
        } else {
            findings.stream()
                    .sorted(Comparator.comparingDouble(Finding::getEstimatedMonthlySavings).reversed())
                    .limit(10)
                    .forEach(f -> {
                        sb.append(String.format(" %-20s (%-4s) - Savings: $%.2f/mo\n", 
                                f.getResourceId(), f.getResourceType(), f.getEstimatedMonthlySavings()));
                        if (f.getDetails() != null && !f.getDetails().isEmpty()) {
                            sb.append("   Details: ").append(f.getDetails().toString()).append("\n");
                        }
                    });
        }
        sb.append("\n==================================================\n");
        sb.append("To view full details and approve/reject remediation actions,\n");
        sb.append("access the Cost Optimization Dashboard UI.\n");
        sb.append("==================================================\n");

        return sb.toString();
    }

    private void publishToSns(String digest) {
        String snsTopicArn = System.getenv("SNS_TOPIC_ARN");
        if (snsTopicArn == null || snsTopicArn.isEmpty()) {
            logger.warn("SNS_TOPIC_ARN environment variable is not set. Skipping SNS publish.");
            return;
        }

        logger.info("Publishing digest to SNS topic: {}", snsTopicArn);
        try (SnsClient snsClient = SnsClient.builder().build()) {
            PublishRequest request = PublishRequest.builder()
                    .topicArn(snsTopicArn)
                    .subject("AWS Cost Optimization Daily Report")
                    .message(digest)
                    .build();
            snsClient.publish(request);
            logger.info("Successfully published digest message to SNS.");
        } catch (Exception e) {
            logger.error("Error publishing digest to SNS: {}", e.getMessage(), e);
        }
    }
}
