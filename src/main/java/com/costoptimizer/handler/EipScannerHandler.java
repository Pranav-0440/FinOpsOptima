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

public class EipScannerHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(EipScannerHandler.class);

    private final DatabaseService databaseService = new DatabaseService();
    private final MockDataService mockDataService = new MockDataService();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Starting EIP Scanner Lambda");

        boolean isMock = "true".equalsIgnoreCase(System.getenv("MOCK_AWS"));
        List<Finding> findings;

        if (isMock) {
            logger.info("Running in MOCK mode. Fetching mock EIP findings.");
            findings = mockDataService.getMockEipFindings();
        } else {
            logger.info("Running in REAL AWS mode.");
            findings = scanRealElasticIps();
        }

        logger.info("Found {} unused Elastic IPs", findings.size());
        for (Finding finding : findings) {
            databaseService.saveFinding(finding);
        }

        return "EIP scan completed. Found and recorded " + findings.size() + " findings.";
    }

    private List<Finding> scanRealElasticIps() {
        List<Finding> findings = new ArrayList<>();
        try (Ec2Client ec2 = Ec2Client.builder().build()) {
            DescribeAddressesResponse response = ec2.describeAddresses();

            for (Address address : response.addresses()) {
                // If there's no association ID, it is unattached and accumulating charges
                if (address.associationId() == null || address.associationId().isEmpty()) {
                    String allocationId = address.allocationId();
                    String publicIp = address.publicIp();
                    
                    // AWS charges $0.005 per hour for unassociated EIPs = ~$3.60/month
                    double monthlySavings = 3.60;

                    Map<String, String> details = new HashMap<>();
                    details.put("PublicIp", publicIp);
                    details.put("AssociationState", "unassociated");

                    findings.add(new Finding(
                            allocationId,
                            "EIP",
                            monthlySavings,
                            Instant.now().toString(),
                            "ACTIVE",
                            details
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error scanning Elastic IPs: {}", e.getMessage(), e);
        }
        return findings;
    }
}
