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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class Ec2ScannerHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(Ec2ScannerHandler.class);
    
    private final DatabaseService databaseService = new DatabaseService();
    private final MockDataService mockDataService = new MockDataService();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Starting EC2 Scanner Lambda");
        
        boolean isMock = "true".equalsIgnoreCase(System.getenv("MOCK_AWS"));
        List<Finding> findings;

        if (isMock) {
            logger.info("Running in MOCK mode. Fetching mock EC2 findings.");
            findings = mockDataService.getMockEc2Findings();
        } else {
            logger.info("Running in REAL AWS mode.");
            findings = scanRealEc2Instances();
        }

        logger.info("Found {} EC2 instances with low utilization", findings.size());
        for (Finding finding : findings) {
            databaseService.saveFinding(finding);
        }

        return "EC2 scan completed. Found and recorded " + findings.size() + " findings.";
    }

    private List<Finding> scanRealEc2Instances() {
        List<Finding> findings = new ArrayList<>();
        try (Ec2Client ec2 = Ec2Client.builder().build();
             CloudWatchClient cw = CloudWatchClient.builder().build()) {

            DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                    .filters(Filter.builder().name("instance-state-name").values("running").build())
                    .build();

            DescribeInstancesResponse describeResponse = ec2.describeInstances(describeRequest);

            for (Reservation reservation : describeResponse.reservations()) {
                for (Instance instance : reservation.instances()) {
                    String instanceId = instance.instanceId();
                    String instanceType = instance.instanceTypeAsString();
                    
                    double avgCpu = getAverageCpuUtilization(cw, instanceId);
                    logger.info("Instance {} ({}) average CPU over last 14 days: {}%", instanceId, instanceType, avgCpu);

                    if (avgCpu >= 0 && avgCpu < 5.0) {
                        double monthlySavings = estimateEc2Savings(instanceType);
                        
                        Map<String, String> details = new HashMap<>();
                        details.put("InstanceType", instanceType);
                        details.put("AvgCpuUtilization", String.format("%.2f%%", avgCpu));
                        details.put("Region", System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
                        
                        // Extract Name tag
                        String name = instance.tags().stream()
                                .filter(t -> t.key().equalsIgnoreCase("Name"))
                                .map(software.amazon.awssdk.services.ec2.model.Tag::value)
                                .findFirst()
                                .orElse("Unnamed-EC2");
                        details.put("Name", name);

                        findings.add(new Finding(
                                instanceId,
                                "EC2",
                                monthlySavings,
                                Instant.now().toString(),
                                "ACTIVE",
                                details
                        ));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error scanning EC2 instances: {}", e.getMessage(), e);
        }
        return findings;
    }

    private double getAverageCpuUtilization(CloudWatchClient cw, String instanceId) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(Duration.ofDays(14));

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/EC2")
                    .metricName("CPUUtilization")
                    .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(86400) // 1 day in seconds
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
            logger.warn("Failed to get CloudWatch CPU utilization for instance {}: {}", instanceId, e.getMessage());
        }
        return -1; // Indicate failure to fetch metrics
    }

    private double estimateEc2Savings(String instanceType) {
        // Simple standard pricing estimates (monthly = hourly * 730)
        return switch (instanceType.toLowerCase()) {
            case "t3.nano" -> 3.80;
            case "t3.micro" -> 7.60;
            case "t3.small" -> 15.20;
            case "t3.medium" -> 30.40;
            case "t3.large" -> 60.80;
            case "t3.xlarge" -> 121.60;
            case "m5.large" -> 70.08;
            case "m5.xlarge" -> 140.16;
            case "c5.large" -> 62.05;
            case "c5.xlarge" -> 124.10;
            default -> 40.00; // default estimated fallback savings
        };
    }
}
