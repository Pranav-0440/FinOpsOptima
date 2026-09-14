package com.costoptimizer.service;

import com.costoptimizer.model.Finding;
import java.time.Instant;
import java.util.*;

public class MockDataService {

    public List<Finding> getMockEc2Findings() {
        List<Finding> findings = new ArrayList<>();
        
        Map<String, String> details1 = new HashMap<>();
        details1.put("InstanceType", "t3.medium");
        details1.put("AvgCpuUtilization", "1.2%");
        details1.put("Region", "us-east-1");
        details1.put("Name", "dev-backend-api");
        findings.add(new Finding(
                "i-0a2b3c4d5e6f7g8h9",
                "EC2",
                62.00,
                Instant.now().toString(),
                "ACTIVE",
                details1
        ));

        Map<String, String> details2 = new HashMap<>();
        details2.put("InstanceType", "m5.large");
        details2.put("AvgCpuUtilization", "2.8%");
        details2.put("Region", "us-east-1");
        details2.put("Name", "staging-analytics");
        findings.add(new Finding(
                "i-9h8g7f6e5d4c3b2a1",
                "EC2",
                134.00,
                Instant.now().toString(),
                "ACTIVE",
                details2
        ));

        return findings;
    }

    public List<Finding> getMockEbsFindings() {
        List<Finding> findings = new ArrayList<>();

        Map<String, String> details1 = new HashMap<>();
        details1.put("VolumeSize", "500 GB");
        details1.put("VolumeType", "gp3");
        details1.put("State", "available (unattached)");
        details1.put("Name", "detached-data-backup");
        findings.add(new Finding(
                "vol-0123456789abcdef0",
                "EBS",
                50.00,
                Instant.now().toString(),
                "ACTIVE",
                details1
        ));

        Map<String, String> details2 = new HashMap<>();
        details2.put("VolumeSize", "100 GB");
        details2.put("VolumeType", "gp2");
        details2.put("State", "available (unattached)");
        details2.put("Name", "temp-scratch-disk");
        findings.add(new Finding(
                "vol-fedcba9876543210",
                "EBS",
                10.00,
                Instant.now().toString(),
                "ACTIVE",
                details2
        ));

        return findings;
    }

    public List<Finding> getMockEipFindings() {
        List<Finding> findings = new ArrayList<>();

        Map<String, String> details1 = new HashMap<>();
        details1.put("PublicIp", "54.210.12.34");
        details1.put("AssociationState", "unassociated");
        findings.add(new Finding(
                "eipalloc-01a2b3c4d5e6f7g8",
                "EIP",
                3.60,
                Instant.now().toString(),
                "ACTIVE",
                details1
        ));

        Map<String, String> details2 = new HashMap<>();
        details2.put("PublicIp", "34.195.45.67");
        details2.put("AssociationState", "unassociated");
        findings.add(new Finding(
                "eipalloc-987654321fedcba0",
                "EIP",
                3.60,
                Instant.now().toString(),
                "ACTIVE",
                details2
        ));

        return findings;
    }

    public List<Finding> getMockRdsFindings() {
        List<Finding> findings = new ArrayList<>();

        Map<String, String> details1 = new HashMap<>();
        details1.put("DBInstanceClass", "db.t3.medium");
        details1.put("Connections14Days", "0");
        details1.put("Engine", "postgres");
        details1.put("Name", "dev-reporting-replica");
        findings.add(new Finding(
                "db-prod-replica-1",
                "RDS",
                49.00,
                Instant.now().toString(),
                "ACTIVE",
                details1
        ));

        Map<String, String> details2 = new HashMap<>();
        details2.put("DBInstanceClass", "db.m5.large");
        details2.put("Connections14Days", "0");
        details2.put("Engine", "mysql");
        details2.put("Name", "test-mysql-db");
        findings.add(new Finding(
                "db-test-mysql",
                "RDS",
                115.00,
                Instant.now().toString(),
                "ACTIVE",
                details2
        ));

        return findings;
    }

    public List<Finding> getMockS3Findings() {
        List<Finding> findings = new ArrayList<>();

        Map<String, String> details1 = new HashMap<>();
        details1.put("BucketSize", "12 TB");
        details1.put("ObjectCount", "1,250,000");
        details1.put("LifecyclePolicy", "None");
        findings.add(new Finding(
                "my-corporate-backup-archive",
                "S3",
                276.00,
                Instant.now().toString(),
                "ACTIVE",
                details1
        ));

        Map<String, String> details2 = new HashMap<>();
        details2.put("BucketSize", "850 GB");
        details2.put("ObjectCount", "45,000");
        details2.put("LifecyclePolicy", "None");
        findings.add(new Finding(
                "temp-reports-bucket-2025",
                "S3",
                19.55,
                Instant.now().toString(),
                "ACTIVE",
                details2
        ));

        return findings;
    }

    public List<Finding> getAllMockFindings() {
        List<Finding> all = new ArrayList<>();
        all.addAll(getMockEc2Findings());
        all.addAll(getMockEbsFindings());
        all.addAll(getMockEipFindings());
        all.addAll(getMockRdsFindings());
        all.addAll(getMockS3Findings());
        return all;
    }
}
