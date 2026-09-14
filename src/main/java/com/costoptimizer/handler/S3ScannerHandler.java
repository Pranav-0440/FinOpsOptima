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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class S3ScannerHandler implements RequestHandler<Map<String, Object>, String> {
    private static final Logger logger = LoggerFactory.getLogger(S3ScannerHandler.class);

    private final DatabaseService databaseService = new DatabaseService();
    private final MockDataService mockDataService = new MockDataService();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        logger.info("Starting S3 Scanner Lambda");

        boolean isMock = "true".equalsIgnoreCase(System.getenv("MOCK_AWS"));
        List<Finding> findings;

        if (isMock) {
            logger.info("Running in MOCK mode. Fetching mock S3 findings.");
            findings = mockDataService.getMockS3Findings();
        } else {
            logger.info("Running in REAL AWS mode.");
            findings = scanRealS3Buckets();
        }

        logger.info("Found {} S3 buckets with no lifecycle policy", findings.size());
        for (Finding finding : findings) {
            databaseService.saveFinding(finding);
        }

        return "S3 scan completed. Found and recorded " + findings.size() + " findings.";
    }

    private List<Finding> scanRealS3Buckets() {
        List<Finding> findings = new ArrayList<>();
        try (S3Client s3 = S3Client.builder().build();
             CloudWatchClient cw = CloudWatchClient.builder().build()) {

            ListBucketsResponse response = s3.listBuckets();

            for (Bucket bucket : response.buckets()) {
                String bucketName = bucket.name();
                boolean hasLifecycle = hasLifecycleRules(s3, bucketName);
                logger.info("S3 Bucket {} has lifecycle rules: {}", bucketName, hasLifecycle);

                if (!hasLifecycle) {
                    double bucketSizeGb = getBucketSizeGb(cw, bucketName);
                    double monthlySavings = bucketSizeGb * 0.023 * 0.40; // Assume 40% savings via Lifecycle (transitions/expiration)

                    Map<String, String> details = new HashMap<>();
                    details.put("BucketSize", String.format("%.2f GB", bucketSizeGb));
                    details.put("LifecyclePolicy", "None");
                    details.put("Name", bucketName);

                    findings.add(new Finding(
                            bucketName,
                            "S3",
                            monthlySavings,
                            Instant.now().toString(),
                            "ACTIVE",
                            details
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error scanning S3 buckets: {}", e.getMessage(), e);
        }
        return findings;
    }

    private boolean hasLifecycleRules(S3Client s3, String bucketName) {
        try {
            GetBucketLifecycleConfigurationRequest request = GetBucketLifecycleConfigurationRequest.builder()
                    .bucket(bucketName)
                    .build();
            GetBucketLifecycleConfigurationResponse response = s3.getBucketLifecycleConfiguration(request);
            return response.rules() != null && !response.rules().isEmpty();
        } catch (S3Exception e) {
            if (e.awsErrorDetails() != null && "NoSuchLifecycleConfiguration".equalsIgnoreCase(e.awsErrorDetails().errorCode())) {
                return false;
            }
            logger.warn("Could not retrieve lifecycle configuration for bucket {}: {}", bucketName, e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warn("Could not retrieve lifecycle configuration for bucket {}: {}", bucketName, e.getMessage());
            return false;
        }
    }

    private double getBucketSizeGb(CloudWatchClient cw, String bucketName) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(Duration.ofDays(2)); // Standard S3 metrics are reported daily

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/S3")
                    .metricName("BucketSizeBytes")
                    .dimensions(
                            Dimension.builder().name("BucketName").value(bucketName).build(),
                            Dimension.builder().name("StorageType").value("StandardStorage").build()
                    )
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(86400) // 1 day
                    .statistics(Statistic.AVERAGE)
                    .build();

            GetMetricStatisticsResponse response = cw.getMetricStatistics(request);
            if (!response.datapoints().isEmpty()) {
                // Get the latest datapoint
                double sizeBytes = response.datapoints().stream()
                        .max(Comparator.comparing(Datapoint::timestamp))
                        .map(Datapoint::average)
                        .orElse(0.0);
                
                return sizeBytes / (1024.0 * 1024.0 * 1024.0); // Convert Bytes to GB
            }
        } catch (Exception e) {
            logger.warn("Failed to get S3 BucketSizeBytes metric for bucket {}: {}", bucketName, e.getMessage());
        }
        return 100.0; // Default fallback size (100 GB) for estimation
    }
}
