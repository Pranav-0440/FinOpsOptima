# AWS Cost Optimization System

A **serverless AWS cost optimization system** built with **Java 21**, **AWS SDK v2**, **Terraform**, and a modern **glassmorphic web dashboard**. It automatically scans your AWS account for idle and unused resources, estimates monthly savings, and offers approval-gated remediation.

> **Resume bullet**: "Built a serverless AWS cost-optimization system using Lambda, EventBridge, Step Functions, and DynamoDB that automatically detects idle/unused resources across EC2, EBS, EIP, RDS, and S3 — estimating monthly savings and reducing manual FinOps review time."

---

## Architecture

```
EventBridge (daily) → Step Functions State Machine
                         ├─ EC2 Scanner Lambda (parallel)
                         ├─ EBS Scanner Lambda (parallel)
                         ├─ EIP Scanner Lambda (parallel)
                         ├─ RDS Scanner Lambda (parallel)
                         └─ S3 Scanner Lambda (parallel)
                                    │
                                    ▼
                         DynamoDB (CostSavingsFindings)
                                    │
                         ┌──────────┴──────────┐
                         ▼                     ▼
                  Summary Lambda          API Gateway
                         │                     │
                         ▼                     ▼
                   SNS → Email/Slack    Web Dashboard (S3)
                                               │
                                               ▼
                                    Remediation Handler
                                    (Stop EC2 / Ignore)
                                               │
                                               ▼
                                    Step Functions Callback
```

## What It Scans

| Service | Detection Criteria | Savings Estimate |
|---------|-------------------|-----------------|
| **EC2** | Running instances with <5% avg CPU over 14 days | Based on instance type pricing |
| **EBS** | Volumes in `available` (unattached) state | Volume size × per-GB pricing |
| **EIP** | Elastic IPs with no association | $3.60/month per unassociated EIP |
| **RDS** | DB instances with <1 avg connection over 14 days | Based on DB instance class pricing |
| **S3** | Buckets with no lifecycle policy configured | 40% of storage cost via lifecycle transitions |

---

## Project Structure

```
├── pom.xml                          # Maven build (Java 21, AWS SDK v2, Shade plugin)
├── src/main/java/com/costoptimizer/
│   ├── model/
│   │   └── Finding.java             # DynamoDB data model
│   ├── service/
│   │   ├── DatabaseService.java     # DynamoDB CRUD operations
│   │   └── MockDataService.java     # Mock data for Lambda testing
│   └── handler/
│       ├── Ec2ScannerHandler.java   # EC2 low-CPU scanner
│       ├── EbsScannerHandler.java   # Unattached EBS volume scanner
│       ├── EipScannerHandler.java   # Unused Elastic IP scanner
│       ├── RdsScannerHandler.java   # Idle RDS instance scanner
│       ├── S3ScannerHandler.java    # S3 lifecycle policy scanner
│       ├── SummaryGeneratorHandler.java  # Aggregation + SNS digest
│       ├── RemediationHandler.java  # Approval-gated stop/ignore
│       └── ApiHandler.java          # API Gateway proxy handler
├── dashboard/                       # Web UI (HTML + CSS + JS)
│   ├── index.html                   # Glassmorphic dashboard layout
│   ├── style.css                    # Dark/light theme, animations
│   └── app.js                       # Dynamic state management, Chart.js
├── terraform/                       # Infrastructure as Code
│   ├── main.tf                      # Provider, variables, outputs
│   ├── dynamodb.tf                  # CostSavingsFindings table
│   ├── lambda.tf                    # All Lambda function definitions
│   ├── iam.tf                       # Least-privilege IAM roles
│   ├── api_gateway.tf               # REST API with CORS
│   ├── step_functions.tf            # Parallel scan orchestration + approval gate
│   ├── sns.tf                       # Alert topic + email subscription
│   └── eventbridge.tf               # Daily scheduled trigger
└── scripts/
    └── local_server.py              # Local dev server (boto3 live scanning)
```

---

## Quick Start

### Prerequisites
- **Java 21** and **Maven** (for building Lambda JARs)
- **Python 3** and **boto3** (for local development server)
- **AWS CLI** configured with valid credentials (`aws configure`)
- **Terraform** (for deploying to AWS)

### 1. Build the Java Backend
```bash
mvn clean package
```
Produces `target/aws-cost-optimization-system-1.0-SNAPSHOT.jar` — an Uber JAR with all dependencies.

### 2. Run Locally (Scans Your Real AWS Account)
```bash
pip install boto3
python scripts/local_server.py
```
- Opens the dashboard at `http://localhost:5000`
- Click **"Trigger Scan Run"** to scan your AWS account
- View findings, approve/reject remediation actions
- Approving an EC2 stop will **actually stop the instance**

### 3. Deploy to AWS (Terraform)
```bash
cd terraform
terraform init
terraform apply -var="mock_aws=false" -var="email_recipient=you@example.com"
```

### 4. Mock Mode (Safe Testing on AWS)
Deploy with `MOCK_AWS=true` (default) to test the full pipeline without scanning real resources:
```bash
terraform apply   # mock_aws defaults to true
```

---

## Dashboard Features

- **KPI Cards**: Total monthly savings, active waste count, remediation rate
- **Doughnut Chart**: Savings breakdown by service type (Chart.js)
- **Findings Table**: Filterable by service type, searchable by resource ID/details
- **Remediation Modal**: Approval gate before stopping EC2/RDS instances
- **Action History**: Log of all remediated and ignored resources
- **Dark/Light Theme**: Toggle with persistent preference
- **Real-time Polling**: Scan progress updates every 2 seconds

---

## Key Technologies

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, AWS SDK v2, AWS Lambda |
| **Orchestration** | AWS Step Functions (parallel + wait-for-callback) |
| **Scheduling** | Amazon EventBridge (rate-based rules) |
| **Storage** | Amazon DynamoDB (on-demand billing) |
| **Alerting** | Amazon SNS (email / webhook) |
| **API** | Amazon API Gateway (REST, CORS) |
| **Dashboard** | HTML5, CSS3 (glassmorphism), Vanilla JS, Chart.js |
| **IaC** | Terraform (HCL) |
| **Local Dev** | Python + boto3 (live AWS scanning) |
