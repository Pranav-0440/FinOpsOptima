package com.costoptimizer.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.costoptimizer.model.Finding;
import com.costoptimizer.service.DatabaseService;
import com.costoptimizer.service.MockDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Instant;
import java.util.*;

public class EbsScannerHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(EbsScannerHandler.class);

    private final DatabaseService databaseService = new DatabaseService();
    private final MockDataService mockDataService = new MockDataService();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Starting EBS Scanner Lambda");

        boolean isMock = "true".equalsIgnoreCase(System.getenv("MOCK_AWS"));
        List<Finding> findings;

        if (isMock) {
            logger.info("Running in MOCK mode. Fetching mock EBS findings.");
            findings = mockDataService.getMockEbsFindings();
        } else {
            logger.info("Running in REAL AWS mode.");
            findings = scanRealEbsVolumes();
        }

        logger.info("Found {} unattached EBS volumes", findings.size());
        for (Finding finding : findings) {
            databaseService.saveFinding(finding);
        }

        return "EBS scan completed. Found and recorded " + findings.size() + " findings.";
    }

    private List<Finding> scanRealEbsVolumes() {
        List<Finding> findings = new ArrayList<>();
        try (Ec2Client ec2 = Ec2Client.builder().build()) {
            // Filter for volumes that are "available" (not in-use/attached)
            DescribeVolumesRequest request = DescribeVolumesRequest.builder()
                    .filters(Filter.builder().name("status").values("available").build())
                    .build();

            DescribeVolumesResponse response = ec2.describeVolumes(request);

            for (Volume volume : response.volumes()) {
                String volumeId = volume.volumeId();
                int sizeGb = volume.size();
                String volumeType = volume.volumeTypeAsString();
                
                double monthlySavings = estimateEbsSavings(sizeGb, volumeType);

                Map<String, String> details = new HashMap<>();
                details.put("VolumeSize", sizeGb + " GB");
                details.put("VolumeType", volumeType);
                details.put("State", "available (unattached)");
                
                String name = volume.tags().stream()
                        .filter(t -> t.key().equalsIgnoreCase("Name"))
                        .map(Tag::value)
                        .findFirst()
                        .orElse("Unnamed-Volume");
                details.put("Name", name);

                findings.add(new Finding(
                        volumeId,
                        "EBS",
                        monthlySavings,
                        Instant.now().toString(),
                        "ACTIVE",
                        details
                ));
            }
        } catch (Exception e) {
            logger.error("Error scanning EBS volumes: {}", e.getMessage(), e);
        }
        return findings;
    }

    private double estimateEbsSavings(int sizeGb, String volumeType) {
        // Standard pricing assumptions: gp3/gp2 = $0.10/GB-month, standard magnetic = $0.05/GB-month, io1/io2 = $0.125/GB-month
        double pricePerGb = switch (volumeType.toLowerCase()) {
            case "gp3", "gp2" -> 0.10;
            case "io1", "io2" -> 0.125;
            case "standard" -> 0.05;
            case "sc1" -> 0.025;
            case "st1" -> 0.045;
            default -> 0.10;
        };
        return sizeGb * pricePerGb;
    }
}
