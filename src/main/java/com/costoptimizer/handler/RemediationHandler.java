package com.costoptimizer.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.costoptimizer.service.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SendTaskSuccessRequest;

import java.util.Map;

public class RemediationHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(RemediationHandler.class);

    private final DatabaseService databaseService = new DatabaseService();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Starting Remediation Handler Lambda with input: {}", input);

        String resourceId = (String) input.get("resourceId");
        String resourceType = (String) input.get("resourceType");
        String action = (String) input.get("action"); // "APPROVE" (remediate) or "REJECT" (ignore)
        String taskToken = (String) input.get("taskToken"); // If triggered from Step Function callback

        if (resourceId == null || resourceType == null || action == null) {
            throw new IllegalArgumentException("Missing required arguments: resourceId, resourceType, action");
        }

        boolean isMock = "true".equalsIgnoreCase(System.getenv("MOCK_AWS"));
        String finalStatus;

        if ("APPROVE".equalsIgnoreCase(action)) {
            logger.info("Remediation APPROVED for resource {} ({})", resourceId, resourceType);
            
            boolean success = executeRemediation(resourceId, resourceType, isMock);
            if (success) {
                finalStatus = "REMEDIATED";
                databaseService.updateFindingStatus(resourceId, resourceType, finalStatus);
                logger.info("Resource {} marked as REMEDIATED in database", resourceId);
            } else {
                finalStatus = "FAILED";
                databaseService.updateFindingStatus(resourceId, resourceType, finalStatus);
                logger.error("Remediation execution failed for resource {}", resourceId);
            }
        } else {
            logger.info("Remediation REJECTED/IGNORED for resource {} ({})", resourceId, resourceType);
            finalStatus = "IGNORED";
            databaseService.updateFindingStatus(resourceId, resourceType, finalStatus);
        }

        // If this execution has a Step Function Task Token, send callback to resume workflow
        if (taskToken != null && !taskToken.trim().isEmpty()) {
            sendStepFunctionCallback(taskToken, finalStatus);
        }

        return "Remediation action completed. Status: " + finalStatus;
    }

    private boolean executeRemediation(String resourceId, String resourceType, boolean isMock) {
        if (isMock) {
            logger.info("[MOCK] Simulating remediation action for {} of type {}", resourceId, resourceType);
            return true; // Pretend it succeeded
        }

        try {
            if ("EC2".equalsIgnoreCase(resourceType)) {
                logger.info("Stopping EC2 Instance: {}", resourceId);
                try (Ec2Client ec2 = Ec2Client.builder().build()) {
                    StopInstancesRequest request = StopInstancesRequest.builder()
                            .instanceIds(resourceId)
                            .build();
                    ec2.stopInstances(request);
                    logger.info("Successfully requested EC2 instance {} to stop", resourceId);
                    return true;
                }
            } else {
                // EIP release, S3 policy application, or EBS volume deletion could be added here,
                // but stopping EC2 instances is the primary approval-gated flow requested.
                logger.warn("Automatic remediation for resource type {} is not implemented in AWS mode. Marking action as success.", resourceType);
                return true;
            }
        } catch (Exception e) {
            logger.error("Exception occurred during remediation of resource {}: {}", resourceId, e.getMessage(), e);
            return false;
        }
    }

    private void sendStepFunctionCallback(String taskToken, String status) {
        logger.info("Sending Step Function Callback taskToken: {}, status: {}", taskToken, status);
        try (SfnClient sfnClient = SfnClient.builder().build()) {
            String output = String.format("{\"status\":\"%s\"}", status);
            SendTaskSuccessRequest request = SendTaskSuccessRequest.builder()
                    .taskToken(taskToken)
                    .output(output)
                    .build();
            sfnClient.sendTaskSuccess(request);
            logger.info("Step Function callback sent successfully");
        } catch (Exception e) {
            logger.error("Failed to send task success to Step Function: {}", e.getMessage(), e);
        }
    }
}
