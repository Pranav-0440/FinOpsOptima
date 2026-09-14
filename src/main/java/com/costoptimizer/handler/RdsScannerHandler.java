package com.costoptimizer.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.costoptimizer.model.Finding;
import com.costoptimizer.service.DatabaseService;
import com.costoptimizer.service.MockDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class RdsScannerHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(RdsScannerHandler.class);

    private final DatabaseService databaseService = new DatabaseService();
    private final MockDataService mockDataService = new MockDataService();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Starting RDS Scanner Lambda");

        boolean isMock = "true".equalsIgnoreCase(System.getenv("MOCK_AWS"));
        List<Finding> findings;

        if (isMock) {
            logger.info("Running in MOCK mode. Fetching mock RDS findings.");
            findings = mockDataService.getMockRdsFindings();
        } else {
            logger.info("Running in REAL AWS mode.");
            findings = scanRealRdsInstances();
        }

        logger.info("Found {} idle RDS instances", findings.size());
        for (Finding finding : findings) {
            databaseService.saveFinding(finding);
        }

        return "RDS scan completed. Found and recorded " + findings.size() + " findings.";
    }

    private List<Finding> scanRealRdsInstances() {
        List<Finding> findings = new ArrayList<>();
        try (RdsClient rds = RdsClient.builder().build();
             CloudWatchClient cw = CloudWatchClient.builder().build()) {

            DescribeDbInstancesResponse response = rds.describeDBInstances();

            for (DBInstance dbInstance : response.dbInstances()) {
                String dbInstanceIdentifier = dbInstance.dbInstanceIdentifier();
                String dbClass = dbInstance.dbInstanceClass();
                String engine = dbInstance.engine();

                // Skip instances that aren't running (e.g. stopped, creating, deleting)
                if (!"available".equalsIgnoreCase(dbInstance.dbInstanceStatus())) {
                    logger.info("DB Instance {} is not in 'available' state (status: {}). Skipping.", dbInstanceIdentifier, dbInstance.dbInstanceStatus());
                    continue;
                }

                double avgConnections = getAverageConnections(cw, dbInstanceIdentifier);
                logger.info("RDS Instance {} average database connections over last 14 days: {}", dbInstanceIdentifier, avgConnections);

                if (avgConnections >= 0 && avgConnections < 1.0) {
                    double monthlySavings = estimateRdsSavings(dbClass);

                    Map<String, String> details = new HashMap<>();
                    details.put("DBInstanceClass", dbClass);
                    details.put("Connections14Days", String.format("%.1f", avgConnections));
                    details.put("Engine", engine);
                    details.put("Name", dbInstanceIdentifier);

                    findings.add(new Finding(
                            dbInstanceIdentifier,
                            "RDS",
                            monthlySavings,
                            Instant.now().toString(),
                            "ACTIVE",
                            details
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error scanning RDS instances: {}", e.getMessage(), e);
        }
        return findings;
    }

    private double getAverageConnections(CloudWatchClient cw, String dbInstanceIdentifier) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(Duration.ofDays(14));

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/RDS")
                    .metricName("DatabaseConnections")
                    .dimensions(Dimension.builder().name("DBInstanceIdentifier").value(dbInstanceIdentifier).build())
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(86400) // 1 day
                    .statistics(Statistic.AVERAGE)
                    .build();

            GetMetricStatisticsResponse response = cw.getMetricStatistics(request);
            if (!response.datapoints().isEmpty()) {
                double total = 0;
                for (Datapoint dp : response.datapoints()) {
                    total += dp.average();
                }
                return total / response.datapoints().size();
            }
        } catch (Exception e) {
            logger.warn("Failed to get DatabaseConnections metric for RDS instance {}: {}", dbInstanceIdentifier, e.getMessage());
        }
        return -1;
    }

    private double estimateRdsSavings(String dbClass) {
        // Average RDS instance costs (on-demand hourly * 730)
        return switch (dbClass.toLowerCase()) {
            case "db.t3.micro" -> 12.41;
            case "db.t3.small" -> 24.82;
            case "db.t3.medium" -> 49.64;
            case "db.m5.large" -> 127.75;
            case "db.m5.xlarge" -> 255.50;
            case "db.r5.large" -> 175.20;
            default -> 60.00; // default estimated savings
        };
    }
}
