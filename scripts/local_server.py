#!/usr/bin/env python3
"""
Local Development Server for AWS Cost Optimization System.
Scans your REAL AWS account using boto3 and serves findings to the dashboard.

Prerequisites:
  - pip install boto3
  - AWS CLI configured: aws configure  (or AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY env vars)

Usage:
  python scripts/local_server.py
"""
import http.server
import json
import os
import sys
import webbrowser
import threading
from datetime import datetime, timezone, timedelta

PORT = 5000
DB_FILE = os.path.join(os.path.dirname(__file__), 'findings_db.json')
DASHBOARD_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'dashboard')

# ─── Persistence ─────────────────────────────────────────────────────────

def load_db():
    if not os.path.exists(DB_FILE):
        save_db([])
        return []
    try:
        with open(DB_FILE, 'r') as f:
            return json.load(f)
    except Exception:
        return []

def save_db(data):
    with open(DB_FILE, 'w') as f:
        json.dump(data, f, indent=2)

# ─── AWS Scanners (boto3 – real account) ─────────────────────────────────

def get_aws_region():
    """Get the configured AWS region, defaulting to us-east-1."""
    try:
        import boto3
        session = boto3.session.Session()
        return session.region_name or 'us-east-1'
    except Exception:
        return 'us-east-1'

def scan_ec2(region):
    """Find running EC2 instances with <5% average CPU over the last 14 days."""
    import boto3
    findings = []
    ec2 = boto3.client('ec2', region_name=region)
    cw = boto3.client('cloudwatch', region_name=region)

    reservations = ec2.describe_instances(
        Filters=[{'Name': 'instance-state-name', 'Values': ['running']}]
    ).get('Reservations', [])

    for res in reservations:
        for inst in res.get('Instances', []):
            instance_id = inst['InstanceId']
            instance_type = inst.get('InstanceType', 'unknown')
            name = next((t['Value'] for t in inst.get('Tags', []) if t['Key'] == 'Name'), 'Unnamed')

            # Query CloudWatch for average CPU over last 14 days
            end = datetime.now(timezone.utc)
            start = end - timedelta(days=14)
            try:
                metrics = cw.get_metric_statistics(
                    Namespace='AWS/EC2',
                    MetricName='CPUUtilization',
                    Dimensions=[{'Name': 'InstanceId', 'Value': instance_id}],
                    StartTime=start, EndTime=end,
                    Period=86400,
                    Statistics=['Average']
                )
                datapoints = metrics.get('Datapoints', [])
                if datapoints:
                    avg_cpu = sum(d['Average'] for d in datapoints) / len(datapoints)
                else:
                    avg_cpu = 0.0  # No data = likely idle
            except Exception:
                avg_cpu = -1

            if 0 <= avg_cpu < 5.0:
                savings = estimate_ec2_savings(instance_type)
                findings.append({
                    'resourceId': instance_id,
                    'resourceType': 'EC2',
                    'estimatedMonthlySavings': savings,
                    'detectedAt': datetime.now(timezone.utc).isoformat(),
                    'status': 'ACTIVE',
                    'details': {
                        'InstanceType': instance_type,
                        'AvgCpuUtilization': f'{avg_cpu:.2f}%',
                        'Region': region,
                        'Name': name
                    }
                })
    return findings

def scan_ebs(region):
    """Find unattached (available) EBS volumes."""
    import boto3
    findings = []
    ec2 = boto3.client('ec2', region_name=region)

    volumes = ec2.describe_volumes(
        Filters=[{'Name': 'status', 'Values': ['available']}]
    ).get('Volumes', [])

    for vol in volumes:
        vol_id = vol['VolumeId']
        size_gb = vol.get('Size', 0)
        vol_type = vol.get('VolumeType', 'gp3')
        name = next((t['Value'] for t in vol.get('Tags', []) if t['Key'] == 'Name'), 'Unnamed')

        price_per_gb = {'gp3': 0.08, 'gp2': 0.10, 'io1': 0.125, 'io2': 0.125, 'sc1': 0.015, 'st1': 0.045, 'standard': 0.05}.get(vol_type, 0.10)
        savings = round(size_gb * price_per_gb, 2)

        findings.append({
            'resourceId': vol_id,
            'resourceType': 'EBS',
            'estimatedMonthlySavings': savings,
            'detectedAt': datetime.now(timezone.utc).isoformat(),
            'status': 'ACTIVE',
            'details': {
                'VolumeSize': f'{size_gb} GB',
                'VolumeType': vol_type,
                'State': 'available (unattached)',
                'Name': name
            }
        })
    return findings

def scan_eip(region):
    """Find Elastic IPs not associated with any resource."""
    import boto3
    findings = []
    ec2 = boto3.client('ec2', region_name=region)

    addresses = ec2.describe_addresses().get('Addresses', [])
    for addr in addresses:
        if not addr.get('AssociationId'):
            alloc_id = addr.get('AllocationId', 'unknown')
            public_ip = addr.get('PublicIp', 'N/A')
            findings.append({
                'resourceId': alloc_id,
                'resourceType': 'EIP',
                'estimatedMonthlySavings': 3.60,
                'detectedAt': datetime.now(timezone.utc).isoformat(),
                'status': 'ACTIVE',
                'details': {
                    'PublicIp': public_ip,
                    'AssociationState': 'unassociated'
                }
            })
    return findings

def scan_rds(region):
    """Find RDS instances with 0 active connections over the last 14 days."""
    import boto3
    findings = []
    rds = boto3.client('rds', region_name=region)
    cw = boto3.client('cloudwatch', region_name=region)

    instances = rds.describe_db_instances().get('DBInstances', [])
    for db in instances:
        if db.get('DBInstanceStatus') != 'available':
            continue

        db_id = db['DBInstanceIdentifier']
        db_class = db.get('DBInstanceClass', 'unknown')
        engine = db.get('Engine', 'unknown')

        end = datetime.now(timezone.utc)
        start = end - timedelta(days=14)
        try:
            metrics = cw.get_metric_statistics(
                Namespace='AWS/RDS',
                MetricName='DatabaseConnections',
                Dimensions=[{'Name': 'DBInstanceIdentifier', 'Value': db_id}],
                StartTime=start, EndTime=end,
                Period=86400,
                Statistics=['Average']
            )
            datapoints = metrics.get('Datapoints', [])
            avg_conn = sum(d['Average'] for d in datapoints) / len(datapoints) if datapoints else 0.0
        except Exception:
            avg_conn = -1

        if 0 <= avg_conn < 1.0:
            savings = estimate_rds_savings(db_class)
            findings.append({
                'resourceId': db_id,
                'resourceType': 'RDS',
                'estimatedMonthlySavings': savings,
                'detectedAt': datetime.now(timezone.utc).isoformat(),
                'status': 'ACTIVE',
                'details': {
                    'DBInstanceClass': db_class,
                    'Connections14Days': f'{avg_conn:.1f}',
                    'Engine': engine,
                    'Name': db_id
                }
            })
    return findings

def scan_s3(region):
    """Find S3 buckets with no lifecycle configuration."""
    import boto3
    findings = []
    s3 = boto3.client('s3', region_name=region)
    cw = boto3.client('cloudwatch', region_name=region)

    buckets = s3.list_buckets().get('Buckets', [])
    for bucket in buckets:
        bucket_name = bucket['Name']

        # Check lifecycle rules
        has_lifecycle = False
        try:
            rules = s3.get_bucket_lifecycle_configuration(Bucket=bucket_name).get('Rules', [])
            has_lifecycle = len(rules) > 0
        except s3.exceptions.ClientError as e:
            if 'NoSuchLifecycleConfiguration' in str(e):
                has_lifecycle = False
            else:
                continue  # Permission denied or other errors, skip

        if not has_lifecycle:
            # Try to get bucket size from CloudWatch
            bucket_size_gb = get_bucket_size(cw, bucket_name)
            savings = round(bucket_size_gb * 0.023 * 0.40, 2)  # Assume 40% savings with lifecycle

            findings.append({
                'resourceId': bucket_name,
                'resourceType': 'S3',
                'estimatedMonthlySavings': savings,
                'detectedAt': datetime.now(timezone.utc).isoformat(),
                'status': 'ACTIVE',
                'details': {
                    'BucketSize': f'{bucket_size_gb:.2f} GB',
                    'LifecyclePolicy': 'None',
                    'Name': bucket_name
                }
            })
    return findings

def get_bucket_size(cw, bucket_name):
    """Get the S3 bucket size in GB from CloudWatch metrics."""
    end = datetime.now(timezone.utc)
    start = end - timedelta(days=2)
    try:
        metrics = cw.get_metric_statistics(
            Namespace='AWS/S3',
            MetricName='BucketSizeBytes',
            Dimensions=[
                {'Name': 'BucketName', 'Value': bucket_name},
                {'Name': 'StorageType', 'Value': 'StandardStorage'}
            ],
            StartTime=start, EndTime=end,
            Period=86400,
            Statistics=['Average']
        )
        datapoints = metrics.get('Datapoints', [])
        if datapoints:
            latest = max(datapoints, key=lambda d: d['Timestamp'])
            return latest['Average'] / (1024 ** 3)
    except Exception:
        pass
    return 0.0  # Cannot determine size

# ─── Pricing Helpers ─────────────────────────────────────────────────────

def estimate_ec2_savings(instance_type):
    prices = {
        't3.nano': 3.80, 't3.micro': 7.60, 't3.small': 15.20,
        't3.medium': 30.40, 't3.large': 60.80, 't3.xlarge': 121.60,
        't2.micro': 8.47, 't2.small': 16.79, 't2.medium': 33.87,
        'm5.large': 70.08, 'm5.xlarge': 140.16,
        'c5.large': 62.05, 'c5.xlarge': 124.10,
        'r5.large': 91.98, 'r5.xlarge': 183.96,
    }
    return prices.get(instance_type, 40.00)

def estimate_rds_savings(db_class):
    prices = {
        'db.t3.micro': 12.41, 'db.t3.small': 24.82, 'db.t3.medium': 49.64,
        'db.t2.micro': 12.70, 'db.t2.small': 25.40,
        'db.m5.large': 127.75, 'db.m5.xlarge': 255.50,
        'db.r5.large': 175.20, 'db.r5.xlarge': 350.40,
    }
    return prices.get(db_class, 60.00)

# ─── Full Account Scan Orchestrator ──────────────────────────────────────

scan_status = {'running': False, 'message': '', 'error': None}

def run_full_scan():
    """Run all 5 scanners against the real AWS account."""
    global scan_status
    scan_status = {'running': True, 'message': 'Starting AWS account scan...', 'error': None}

    region = get_aws_region()
    all_findings = []
    scanner_names = ['EC2', 'EBS', 'EIP', 'RDS', 'S3']
    scanners = [
        ('EC2', scan_ec2),
        ('EBS', scan_ebs),
        ('EIP', scan_eip),
        ('RDS', scan_rds),
        ('S3', scan_s3),
    ]

    for name, scanner_fn in scanners:
        scan_status['message'] = f'Scanning {name} resources...'
        print(f'  [SCAN] Scanning {name} in {region}...')
        try:
            results = scanner_fn(region)
            all_findings.extend(results)
            print(f'  [SCAN] {name}: Found {len(results)} waste item(s)')
        except Exception as e:
            error_msg = f'{name} scanner error: {str(e)}'
            print(f'  [ERROR] {error_msg}')
            scan_status['error'] = error_msg

    # Merge with existing DB: preserve REMEDIATED/IGNORED statuses
    existing = load_db()
    existing_map = {(f['resourceId'], f['resourceType']): f for f in existing}

    merged = []
    for finding in all_findings:
        key = (finding['resourceId'], finding['resourceType'])
        if key in existing_map and existing_map[key]['status'] in ('REMEDIATED', 'IGNORED'):
            # Keep the old resolved status
            merged.append(existing_map[key])
        else:
            merged.append(finding)

    save_db(merged)
    total_savings = sum(f['estimatedMonthlySavings'] for f in merged if f['status'] == 'ACTIVE')
    scan_status = {
        'running': False,
        'message': f'Scan complete! Found {len(all_findings)} resources. Potential savings: ${total_savings:.2f}/mo',
        'error': None
    }
    print(f'  [DONE] Scan complete. {len(merged)} total findings. ${total_savings:.2f}/mo potential savings.')

# ─── HTTP Request Handler ────────────────────────────────────────────────

class CostOptimizerHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DASHBOARD_DIR, **kwargs)

    def log_message(self, format, *args):
        # Quieter log: only log API calls, not static file requests
        if '/api/' in (args[0] if args else ''):
            super().log_message(format, *args)

    def send_cors_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')

    def send_json(self, status_code, data):
        self.send_response(status_code)
        self.send_header('Content-Type', 'application/json')
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps(data).encode('utf-8'))

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        if self.path == '/api/findings':
            findings = load_db()
            self.send_json(200, findings)

        elif self.path == '/api/scan/status':
            self.send_json(200, scan_status)

        else:
            # Serve dashboard static files
            if not os.path.exists(os.path.join(DASHBOARD_DIR, self.path.lstrip('/'))):
                self.path = '/index.html'
            super().do_GET()

    def do_POST(self):
        if self.path == '/api/scan':
            if scan_status.get('running'):
                self.send_json(409, {'error': 'Scan already in progress'})
                return

            # Launch scan in a background thread so the HTTP response returns immediately
            thread = threading.Thread(target=run_full_scan, daemon=True)
            thread.start()
            self.send_json(202, {'status': 'accepted', 'message': 'AWS account scan started in background'})

        elif self.path == '/api/remediate':
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            payload = json.loads(post_data.decode('utf-8'))

            resource_id = payload.get('resourceId')
            resource_type = payload.get('resourceType')
            action = payload.get('action', 'REJECT')

            findings = load_db()
            updated = False
            for item in findings:
                if item['resourceId'] == resource_id:
                    new_status = 'REMEDIATED' if action == 'APPROVE' else 'IGNORED'
                    item['status'] = new_status

                    # If APPROVE and it's an EC2 instance, actually stop it
                    if action == 'APPROVE' and resource_type == 'EC2':
                        try:
                            import boto3
                            region = get_aws_region()
                            ec2 = boto3.client('ec2', region_name=region)
                            ec2.stop_instances(InstanceIds=[resource_id])
                            print(f'  [REMEDIATE] Stopped EC2 instance: {resource_id}')
                        except Exception as e:
                            print(f'  [ERROR] Failed to stop EC2 instance {resource_id}: {e}')

                    updated = True
                    break

            if updated:
                save_db(findings)
                self.send_json(200, {'status': 'success', 'message': f"Action '{action}' processed for {resource_id}"})
            else:
                self.send_json(404, {'error': 'Resource not found in findings database'})
        else:
            self.send_json(404, {'error': 'Endpoint not found'})


# ─── Main ────────────────────────────────────────────────────────────────

def main():
    # Start with empty DB if it doesn't exist yet
    if not os.path.exists(DB_FILE):
        save_db([])

    server_address = ('', PORT)
    httpd = http.server.HTTPServer(server_address, CostOptimizerHandler)

    url = f'http://localhost:{PORT}/index.html'
    print('=' * 56)
    print('   AWS COST OPTIMIZATION SYSTEM — LIVE SCANNER MODE')
    print('=' * 56)
    print(f'  Dashboard : {url}')
    print(f'  API       : http://localhost:{PORT}/api/findings')
    print(f'  Database  : {DB_FILE}')
    print(f'  Region    : {get_aws_region()}')
    print()
    print('  Click "Trigger Scan Run" in the dashboard to scan')
    print('  your real AWS account for idle/unused resources.')
    print()
    print('  Press Ctrl+C to stop.')
    print('=' * 56)

    try:
        webbrowser.open(url)
    except Exception:
        pass

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print('\nStopping server...')
        sys.exit(0)

if __name__ == '__main__':
    main()
