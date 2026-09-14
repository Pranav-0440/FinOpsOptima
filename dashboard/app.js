// App Logic for FinOpsOptima Dashboard — Dynamic AWS Mode

// API Configuration
const API_BASE_URL = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
    ? 'http://localhost:5000/api'
    : '/api';

// State Management
let state = {
    findings: [],
    activeFilter: 'all',
    searchQuery: '',
    selectedResource: null,
    savingsChart: null,
    isScanning: false,
    scanPollTimer: null
};

// Initialize Dashboard
document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    initTabs();
    initEventListeners();
    fetchFindings();
});

// Theme Setup
function initTheme() {
    const isLightMode = localStorage.getItem('theme') === 'light';
    if (isLightMode) {
        document.body.classList.remove('dark-mode');
        document.body.classList.add('light-mode');
    }
}

// Navigation Tabs
function initTabs() {
    const menuItems = document.querySelectorAll('.sidebar-menu li');
    const tabContents = document.querySelectorAll('.tab-content');

    menuItems.forEach(item => {
        item.addEventListener('click', () => {
            const tabName = item.getAttribute('data-tab');
            menuItems.forEach(i => i.classList.remove('active'));
            item.classList.add('active');
            tabContents.forEach(content => {
                content.classList.toggle('hidden', content.id !== `${tabName}-tab`);
            });
        });
    });
}

// Event Listeners
function initEventListeners() {
    // Theme Toggle
    document.getElementById('themeToggleBtn').addEventListener('click', () => {
        document.body.classList.toggle('dark-mode');
        document.body.classList.toggle('light-mode');
        localStorage.setItem('theme', document.body.classList.contains('light-mode') ? 'light' : 'dark');
        renderSavingsChart();
    });

    // Refresh Button
    document.getElementById('refreshBtn').addEventListener('click', () => fetchFindings());

    // Trigger Scan Button
    document.getElementById('triggerScanBtn').addEventListener('click', triggerCostScan);

    // Search Input
    document.getElementById('searchInput').addEventListener('input', (e) => {
        state.searchQuery = e.target.value.toLowerCase();
        renderFindingsTable();
    });

    // Filter Buttons
    const filterBtns = document.querySelectorAll('.filter-btn');
    filterBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            filterBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            state.activeFilter = btn.getAttribute('data-filter');
            renderFindingsTable();
        });
    });

    // Modal
    document.getElementById('closeModalBtn').addEventListener('click', hideRemediationModal);
    document.getElementById('confirmModal').addEventListener('click', (e) => {
        if (e.target.id === 'confirmModal') hideRemediationModal();
    });
    document.getElementById('modalApproveBtn').addEventListener('click', () => executeAction('APPROVE'));
    document.getElementById('modalRejectBtn').addEventListener('click', () => executeAction('REJECT'));
}

// ─── Fetch Findings from API ────────────────────────────────────────────

async function fetchFindings() {
    const refreshBtn = document.getElementById('refreshBtn');
    const refreshIcon = refreshBtn.querySelector('i');
    refreshIcon.classList.add('fa-spin');

    try {
        const response = await fetch(`${API_BASE_URL}/findings`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        state.findings = await response.json();
        document.getElementById('envText').innerText = 'AWS Connected';
        showToast('Findings synced from your AWS account', 'success');
    } catch (error) {
        console.error('Failed to fetch findings:', error);
        state.findings = [];
        document.getElementById('envText').innerText = 'API Offline';
        showToast('Cannot reach API server. Is local_server.py running?', 'error');
    } finally {
        refreshIcon.classList.remove('fa-spin');
        updateDashboardUI();
    }
}

// ─── Trigger Live AWS Scan ──────────────────────────────────────────────

async function triggerCostScan() {
    if (state.isScanning) return;

    const btn = document.getElementById('triggerScanBtn');
    btn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Scanning AWS...';
    btn.disabled = true;
    state.isScanning = true;

    try {
        const response = await fetch(`${API_BASE_URL}/scan`, { method: 'POST' });
        const data = await response.json();

        if (response.status === 409) {
            showToast('A scan is already in progress', 'info');
        } else if (response.ok) {
            showToast('AWS account scan started — scanning EC2, EBS, EIP, RDS, S3...', 'info');
            // Poll for scan completion
            pollScanStatus(btn);
            return; // Don't reset button yet; polling will do it
        } else {
            throw new Error(data.error || 'Scan trigger failed');
        }
    } catch (e) {
        console.error('Scan trigger error:', e);
        showToast(`Scan failed: ${e.message}`, 'error');
    }

    btn.innerHTML = '<i class="fa-solid fa-bolt"></i> Trigger Scan Run';
    btn.disabled = false;
    state.isScanning = false;
}

function pollScanStatus(btn) {
    if (state.scanPollTimer) clearInterval(state.scanPollTimer);

    state.scanPollTimer = setInterval(async () => {
        try {
            const res = await fetch(`${API_BASE_URL}/scan/status`);
            const status = await res.json();

            if (!status.running) {
                clearInterval(state.scanPollTimer);
                state.scanPollTimer = null;
                state.isScanning = false;
                btn.innerHTML = '<i class="fa-solid fa-bolt"></i> Trigger Scan Run';
                btn.disabled = false;

                if (status.error) {
                    showToast(`Scan completed with warnings: ${status.error}`, 'error');
                } else {
                    showToast(status.message || 'Scan completed!', 'success');
                }
                fetchFindings(); // Reload the fresh data
            }
        } catch (e) {
            // If we can't reach the server, stop polling
            clearInterval(state.scanPollTimer);
            state.scanPollTimer = null;
            state.isScanning = false;
            btn.innerHTML = '<i class="fa-solid fa-bolt"></i> Trigger Scan Run';
            btn.disabled = false;
        }
    }, 2000); // Poll every 2 seconds
}

// ─── Dashboard UI Updates ───────────────────────────────────────────────

function updateDashboardUI() {
    const activeFindings = state.findings.filter(f => f.status === 'ACTIVE');
    const remediatedFindings = state.findings.filter(f => f.status === 'REMEDIATED');
    const totalCount = state.findings.length;

    // KPI Cards
    const totalSavingsVal = activeFindings.reduce((sum, item) => sum + item.estimatedMonthlySavings, 0);
    document.getElementById('totalSavings').innerText = `$${totalSavingsVal.toFixed(2)}`;
    document.getElementById('activeCount').innerText = activeFindings.length;

    const urgentText = document.getElementById('urgentText');
    if (activeFindings.length > 5) {
        urgentText.innerText = 'Critical review needed!';
        urgentText.className = 'warning-text urgent';
    } else if (activeFindings.length === 0) {
        urgentText.innerText = 'No waste detected';
        urgentText.className = 'positive-trend';
    } else {
        urgentText.innerText = 'Awaiting FinOps review';
        urgentText.className = 'warning-text';
    }

    document.getElementById('remediatedCount').innerText = remediatedFindings.length;
    document.getElementById('totalLoggedCount').innerText = totalCount;
    const rate = totalCount > 0 ? Math.round((remediatedFindings.length / totalCount) * 100) : 0;
    document.getElementById('remediationRate').innerText = `${rate}%`;

    renderFindingsTable();
    renderHistoryTable();
    renderSavingsChart();
    renderCandidateList(activeFindings);
}

// ─── Top Candidate List ─────────────────────────────────────────────────

function renderCandidateList(activeFindings) {
    const listEl = document.getElementById('candidateList');
    listEl.innerHTML = '';

    const sorted = [...activeFindings]
        .sort((a, b) => b.estimatedMonthlySavings - a.estimatedMonthlySavings)
        .slice(0, 4);

    if (sorted.length === 0) {
        listEl.innerHTML = `
            <div class="empty-state">
                <i class="fa-solid fa-circle-check"></i>
                <p>No active remediation candidates!</p>
            </div>`;
        return;
    }

    sorted.forEach(c => {
        const item = document.createElement('div');
        item.className = 'candidate-item';
        const name = c.details?.Name || c.resourceId;
        item.innerHTML = `
            <div class="candidate-info">
                <h4><i class="${getServiceIcon(c.resourceType)}" style="margin-right:8px;color:var(--primary-color);"></i>${truncate(name, 20)}</h4>
                <span>Type: ${c.resourceType} | ID: ${truncate(c.resourceId, 16)}</span>
            </div>
            <div class="candidate-savings">
                <strong>$${c.estimatedMonthlySavings.toFixed(2)}/mo</strong>
                <span>Waste</span>
            </div>`;
        listEl.appendChild(item);
    });
}

// ─── Active Findings Table ──────────────────────────────────────────────

function renderFindingsTable() {
    const tbody = document.getElementById('findingsTableBody');
    tbody.innerHTML = '';

    const filtered = state.findings.filter(f => {
        if (f.status !== 'ACTIVE') return false;
        const matchesFilter = state.activeFilter === 'all' || f.resourceType === state.activeFilter;
        const detailsString = Object.entries(f.details || {}).map(([k, v]) => `${k}:${v}`).join(' ').toLowerCase();
        const matchesSearch = f.resourceId.toLowerCase().includes(state.searchQuery) ||
                              f.resourceType.toLowerCase().includes(state.searchQuery) ||
                              detailsString.includes(state.searchQuery);
        return matchesFilter && matchesSearch;
    });

    if (filtered.length === 0) {
        tbody.innerHTML = `
            <tr><td colspan="6" style="text-align:center;padding:60px;">
                <div class="empty-state">
                    <i class="fa-solid fa-magnifying-glass"></i>
                    <p>${state.findings.length === 0
                        ? 'No findings yet — click <strong>Trigger Scan Run</strong> to scan your AWS account.'
                        : 'No active waste findings match the current filter.'}</p>
                </div>
            </td></tr>`;
        return;
    }

    filtered.forEach(f => {
        const row = document.createElement('tr');
        let detailsHtml = '<div class="details-box">';
        if (f.details) {
            Object.entries(f.details).forEach(([key, val]) => {
                if (key !== 'Name') detailsHtml += `<span class="detail-tag"><strong>${key}:</strong> ${val}</span>`;
            });
        }
        detailsHtml += '</div>';

        const resourceName = f.details?.Name ? `<div><span style="font-size:12px;color:var(--text-secondary);">${f.details.Name}</span></div>` : '';

        row.innerHTML = `
            <td><strong>${f.resourceId}</strong>${resourceName}</td>
            <td><span class="badge badge-${f.resourceType.toLowerCase()}">${f.resourceType}</span></td>
            <td><strong style="color:var(--success-color);font-family:Outfit;">$${f.estimatedMonthlySavings.toFixed(2)}</strong></td>
            <td>${formatDate(f.detectedAt)}</td>
            <td>${detailsHtml}</td>
            <td>${getActionButtons(f)}</td>`;

        const stopBtn = row.querySelector('.remediate-action-btn');
        if (stopBtn) stopBtn.addEventListener('click', () => showRemediationModal(f));

        const ackBtn = row.querySelector('.acknowledge-action-btn');
        if (ackBtn) {
            ackBtn.addEventListener('click', () => {
                state.selectedResource = f;
                executeAction('REJECT');
            });
        }
        tbody.appendChild(row);
    });
}

// ─── Action History Table ───────────────────────────────────────────────

function renderHistoryTable() {
    const tbody = document.getElementById('historyTableBody');
    tbody.innerHTML = '';

    const historyItems = state.findings.filter(f => f.status === 'REMEDIATED' || f.status === 'IGNORED');

    if (historyItems.length === 0) {
        tbody.innerHTML = `
            <tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-muted);">
                No resolution actions logged yet.
            </td></tr>`;
        return;
    }

    historyItems.forEach(f => {
        const row = document.createElement('tr');
        const badgeClass = f.status === 'REMEDIATED' ? 'badge-remediated' : 'badge-ignored';
        row.innerHTML = `
            <td><strong>${f.resourceId}</strong></td>
            <td><span class="badge badge-${f.resourceType.toLowerCase()}">${f.resourceType}</span></td>
            <td><strong style="color:${f.status === 'REMEDIATED' ? 'var(--success-color)' : 'var(--text-muted)'}">$${f.estimatedMonthlySavings.toFixed(2)}</strong></td>
            <td>${formatDate(f.detectedAt)}</td>
            <td><span class="badge ${badgeClass}">${f.status}</span></td>`;
        tbody.appendChild(row);
    });
}

// ─── Action Buttons & Remediation Modal ─────────────────────────────────

function getActionButtons(finding) {
    if (finding.resourceType === 'EC2' || finding.resourceType === 'RDS') {
        return `<button class="btn btn-secondary remediate-action-btn" style="padding:8px 12px;font-size:12px;background:rgba(239,68,68,0.1);border-color:rgba(239,68,68,0.2);color:var(--danger-color);">
            <i class="fa-solid fa-circle-stop"></i> Stop</button>`;
    }
    return `<button class="btn btn-secondary acknowledge-action-btn" style="padding:8px 12px;font-size:12px;color:var(--text-muted);">
        <i class="fa-solid fa-check"></i> Ignore</button>`;
}

function showRemediationModal(finding) {
    state.selectedResource = finding;
    document.getElementById('modalResourceId').innerText = finding.resourceId;
    document.getElementById('modalResourceType').innerText = finding.resourceType;
    document.getElementById('modalSavings').innerText = `$${finding.estimatedMonthlySavings.toFixed(2)}/mo`;
    document.getElementById('confirmModal').classList.remove('hidden');
}

function hideRemediationModal() {
    document.getElementById('confirmModal').classList.add('hidden');
    state.selectedResource = null;
}

async function executeAction(action) {
    const resource = state.selectedResource;
    if (!resource) return;

    try {
        const response = await fetch(`${API_BASE_URL}/remediate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                resourceId: resource.resourceId,
                resourceType: resource.resourceType,
                action: action,
                taskToken: resource.taskToken || ''
            })
        });

        if (response.ok) {
            const result = await response.json();
            showToast(result.message || `Action '${action}' completed`, 'success');

            // Update local state immediately
            const item = state.findings.find(f => f.resourceId === resource.resourceId);
            if (item) item.status = action === 'APPROVE' ? 'REMEDIATED' : 'IGNORED';
        } else {
            const err = await response.json();
            showToast(`Action failed: ${err.error || 'Unknown error'}`, 'error');
        }
    } catch (e) {
        showToast(`Network error: ${e.message}`, 'error');
    }

    hideRemediationModal();
    updateDashboardUI();
}

// ─── Chart.js Doughnut ──────────────────────────────────────────────────

function renderSavingsChart() {
    const activeFindings = state.findings.filter(f => f.status === 'ACTIVE');
    const groups = { EC2: 0, EBS: 0, EIP: 0, RDS: 0, S3: 0 };
    activeFindings.forEach(f => {
        if (groups[f.resourceType] !== undefined) groups[f.resourceType] += f.estimatedMonthlySavings;
    });

    const isDark = document.body.classList.contains('dark-mode');
    const labelColor = isDark ? '#f3f4f6' : '#111827';
    const ctx = document.getElementById('savingsChart').getContext('2d');

    if (state.savingsChart) state.savingsChart.destroy();

    const dataValues = [groups.EC2, groups.EBS, groups.EIP, groups.RDS, groups.S3];
    const totalSavings = dataValues.reduce((a, b) => a + b, 0);

    if (totalSavings === 0) {
        state.savingsChart = new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: ['No Active Waste'],
                datasets: [{ data: [1], backgroundColor: [isDark ? '#1f2937' : '#e5e7eb'], borderWidth: 0 }]
            },
            options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } } }
        });
        return;
    }

    state.savingsChart = new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['EC2 Instances', 'EBS Volumes', 'Elastic IPs', 'RDS Databases', 'S3 Storage'],
            datasets: [{
                data: dataValues,
                backgroundColor: ['#6366f1', '#06b6d4', '#f59e0b', '#10b981', '#ef4444'],
                borderWidth: isDark ? 2 : 1,
                borderColor: isDark ? '#161c2d' : '#ffffff',
                hoverOffset: 12
            }]
        },
        options: {
            responsive: true, maintainAspectRatio: false, cutout: '65%',
            plugins: {
                legend: {
                    position: 'right',
                    labels: { color: labelColor, font: { family: 'Plus Jakarta Sans', size: 13, weight: '500' }, padding: 18 }
                },
                tooltip: {
                    callbacks: {
                        label: ctx => ` $${ctx.raw.toFixed(2)}/mo (${Math.round((ctx.raw / totalSavings) * 100)}%)`
                    }
                }
            }
        }
    });
}

// ─── Helpers ────────────────────────────────────────────────────────────

function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    const iconClass = type === 'success' ? 'fa-circle-check' : type === 'error' ? 'fa-circle-xmark' : 'fa-info-circle';
    toast.innerHTML = `<i class="fa-solid ${iconClass}"></i><span>${message}</span>`;
    container.appendChild(toast);
    setTimeout(() => {
        toast.style.animation = 'slideUpIn 0.3s reverse forwards';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

function formatDate(dateStr) {
    if (!dateStr) return 'N/A';
    return new Date(dateStr).toLocaleDateString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function truncate(str, max) {
    return !str ? '' : str.length > max ? str.substr(0, max - 3) + '...' : str;
}

function getServiceIcon(type) {
    return { EC2: 'fa-solid fa-server', EBS: 'fa-solid fa-hard-drive', EIP: 'fa-solid fa-network-wired', RDS: 'fa-solid fa-database', S3: 'fa-solid fa-bucket' }[type] || 'fa-solid fa-cloud';
}
