const form = document.getElementById('txn-form');
const submitBtn = document.getElementById('submit-btn');
const errorBox = document.getElementById('form-error');
const ledgerBody = document.getElementById('ledger-body');
const ticker = document.getElementById('pipeline-ticker');

const STAGE_ORDER = ['RECORDED', 'QUEUED', 'SCORED', 'ALERTED', 'SETTLED'];

async function loadConfig() {
  try {
    const res = await fetch('/api/health');
    const cfg = await res.json();
    document.getElementById('cfg-region').textContent = cfg.region ?? '—';
    document.getElementById('cfg-table').textContent = cfg.dynamoDbTable ?? '—';
    document.getElementById('cfg-bucket').textContent = cfg.s3Bucket ?? '—';
    document.getElementById('cfg-role').textContent = (cfg.roleArn ?? '—').split('/').pop();
    document.getElementById('cfg-encryption').textContent = cfg.kmsEnabled ? 'KMS ($)' : 'AES256 (free)';

    const badge = document.getElementById('kms-badge');
    badge.textContent = cfg.encryptionMode ?? '—';
    badge.classList.toggle('off', !cfg.kmsEnabled);
  } catch (e) {
    // dashboard still works without this — it's informational only
  }
}

function pillClass(stage) {
  switch (stage) {
    case 'RECORDED': return 'pill-recorded';
    case 'QUEUED': return 'pill-queued';
    case 'SCORED': return 'pill-scored';
    case 'ALERTED': return 'pill-alerted';
    case 'SETTLED': return 'pill-settled';
    default: return 'pill-recorded';
  }
}

function rowStatusClass(entry) {
  if (entry.status === 'DUPLICATE') return 'status-duplicate';
  if (entry.stage === 'ALERTED') return 'status-alerted';
  return 'status-approved';
}

function renderRisk(entry) {
  if (entry.riskScore === null || entry.riskScore === undefined) {
    return '<span class="mono" style="color: var(--muted)">pending…</span>';
  }
  const pct = Math.round(entry.riskScore * 100);
  const high = entry.riskScore > 0.75;
  return `
    <div class="risk-bar-wrap">
      <div class="risk-bar"><div class="risk-bar-fill ${high ? 'high' : ''}" style="width:${pct}%"></div></div>
      <span class="mono">${pct}%</span>
    </div>`;
}

function renderReceipt(entry) {
  if (!entry.receiptKey) return '<span style="color: var(--muted)">—</span>';
  return `<span class="receipt-link" title="${entry.receiptKey}">s3://…/${entry.receiptKey.split('/').pop()}</span>`;
}

function renderAction(entry) {
  if (entry.stage !== 'ALERTED') {
    return '<span style="color: var(--muted)">—</span>';
  }
  return `<button class="release-btn" data-txn-id="${entry.transactionId}">Release</button>`;
}

function renderRow(entry) {
  const tr = document.createElement('tr');
  tr.className = rowStatusClass(entry);
  tr.innerHTML = `
    <td class="mono">${entry.transactionId.slice(0, 8)}…</td>
    <td>${entry.merchantId}</td>
    <td class="mono">${entry.maskedPan}</td>
    <td class="align-right">${entry.amount} ${entry.currency}</td>
    <td><span class="pill ${pillClass(entry.stage)}">${entry.stage.toLowerCase()}</span></td>
    <td>${renderRisk(entry)}</td>
    <td>${renderReceipt(entry)}</td>
    <td>${renderAction(entry)}</td>
  `;
  return tr;
}

function litUpTo(stage) {
  const idx = STAGE_ORDER.indexOf(stage);
  ticker.querySelectorAll('.ticker-stage').forEach((el) => {
    const elIdx = STAGE_ORDER.indexOf(el.dataset.stage);
    el.classList.remove('lit', 'lit-alert');
    if (elIdx <= idx) {
      el.classList.add(stage === 'ALERTED' && elIdx === idx ? 'lit-alert' : 'lit');
    }
  });
}

async function refreshLedger() {
  try {
    const res = await fetch('/api/transactions/recent');
    const entries = await res.json();

    ledgerBody.innerHTML = '';
    if (entries.length === 0) {
      ledgerBody.innerHTML = '<tr class="empty-row"><td colspan="8">No transactions yet — authorize one on the left.</td></tr>';
      return;
    }

    for (const entry of entries) {
      ledgerBody.appendChild(renderRow(entry));
    }

    litUpTo(entries[0].stage);
  } catch (e) {
    // transient network hiccup — next poll will retry
  }
}

form.addEventListener('submit', async (evt) => {
  evt.preventDefault();
  errorBox.hidden = true;
  submitBtn.disabled = true;
  submitBtn.textContent = 'Authorizing…';

  const data = Object.fromEntries(new FormData(form).entries());
  data.pan = data.pan.replace(/\s+/g, '');

  try {
    const res = await fetch('/api/transactions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });

    if (!res.ok) {
      const msg = await res.text();
      throw new Error(msg || `Request failed (${res.status})`);
    }

    form.reset();
    await refreshLedger();
  } catch (err) {
    errorBox.textContent = err.message;
    errorBox.hidden = false;
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Authorize';
  }
});

loadConfig();
refreshLedger();
setInterval(refreshLedger, 2000);

const settlementBtn = document.getElementById('settlement-btn');
const settlementResult = document.getElementById('settlement-result');

settlementBtn.addEventListener('click', async () => {
  settlementBtn.disabled = true;
  settlementBtn.textContent = 'Settling…';
  settlementResult.textContent = '';

  try {
    const res = await fetch('/api/settlement/run', { method: 'POST' });
    if (!res.ok) {
      throw new Error(await res.text() || `Request failed (${res.status})`);
    }
    const result = await res.json();
    settlementResult.textContent = result.transactionCount === 0
      ? 'nothing pending — everything already settled'
      : `settled ${result.transactionCount} txn(s), ${result.totalAmount} total — batch ${result.batchId}`;
    await refreshLedger();
  } catch (err) {
    settlementResult.textContent = 'error: ' + err.message;
  } finally {
    settlementBtn.disabled = false;
    settlementBtn.textContent = 'Run settlement batch';
  }
});

// Release buttons are recreated on every poll, so delegate from the table body
// rather than attaching a listener to each row individually.
ledgerBody.addEventListener('click', async (evt) => {
  const btn = evt.target.closest('.release-btn');
  if (!btn) return;

  const transactionId = btn.dataset.txnId;
  btn.disabled = true;
  btn.textContent = '…';

  try {
    const res = await fetch(`/api/transactions/${transactionId}/release`, { method: 'POST' });
    if (!res.ok) {
      throw new Error(await res.text() || `Request failed (${res.status})`);
    }
    await refreshLedger();
  } catch (err) {
    btn.disabled = false;
    btn.textContent = 'Release';
    alert('Failed to release: ' + err.message);
  }
});
