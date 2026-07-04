// QR code scanning using jsQR library.
// Loaded from CDN: https://cdn.jsdelivr.net/npm/jsqr/dist/jsQR.js

let qrStream = null;
let qrAnimFrame = null;
const qrRecentScans = new Map();
const qrDailyLocks = new Set();
const qrProcessingLocks = new Set();
const qrInvalidLocks = new Set();
const QR_SCAN_COOLDOWN_MS = 8000;

function qrLocalDate(date = new Date()) {
  const localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return localDate.toISOString().split('T')[0];
}

function qrLocalTime(date = new Date()) {
  return date.toTimeString().split(' ')[0];
}

function parseStudentQrPayload(raw) {
  const normalized = String(raw || '').trim();
  const parts = normalized.split('|').map(item => item.trim());
  if (parts.length < 6) return null;
  const systemKey = parts[0].toUpperCase();
  const qrRole = parts[1].toUpperCase();
  const isSupportedQr = systemKey === 'LATTELETTERS' && qrRole === 'STAFF';
  if (!isSupportedQr) return null;
  if (!/^\d+$/.test(parts[2])) return null;
  if (!parts[3]) return null;
  if (!parts[4]) return null;
  if (!parts[5]) return null;
  return {
    studentRef: parts[2],
    studentNumber: parts[3],
    qrVersion: parts[4]
  };
}

async function startQRScan() {
  try {
    qrRecentScans.clear();
    qrDailyLocks.clear();
    qrProcessingLocks.clear();
    qrInvalidLocks.clear();
    qrStream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 1280 },
        height: { ideal: 720 }
      }
    });
    const video = document.getElementById('qrVideo');
    video.srcObject = qrStream;
    video.setAttribute('playsinline', 'true');
    video.muted = true;
    video.play();

    if (!window.jsQR) {
      const script = document.createElement('script');
      script.src = 'https://cdn.jsdelivr.net/npm/jsqr/dist/jsQR.js';
      script.onload = () => scanLoop();
      document.head.appendChild(script);
    } else {
      scanLoop();
    }
    showToast('Camera started', 'success');
  } catch (e) {
    showToast('Camera access denied or not available', 'error');
  }
}

function scanLoop() {
  const video = document.getElementById('qrVideo');
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');

  function tick() {
    if (video.readyState === video.HAVE_ENOUGH_DATA) {
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const code = jsQR(imageData.data, imageData.width, imageData.height, { inversionAttempts: 'attemptBoth' });
      if (code && code.data) {
        const normalized = String(code.data).trim();
        const now = Date.now();
        const lastSeen = qrRecentScans.get(normalized) || 0;
        if (normalized && now - lastSeen > QR_SCAN_COOLDOWN_MS) {
          qrRecentScans.set(normalized, now);
          processQRData(normalized);
        }
      }
    }
    qrAnimFrame = requestAnimationFrame(tick);
  }

  tick();
}

function stopQRScan() {
  if (qrStream) {
    qrStream.getTracks().forEach(track => track.stop());
    qrStream = null;
  }
  if (qrAnimFrame) {
    cancelAnimationFrame(qrAnimFrame);
    qrAnimFrame = null;
  }
  qrRecentScans.clear();
  qrDailyLocks.clear();
  qrProcessingLocks.clear();
  qrInvalidLocks.clear();
  showToast('Camera stopped', 'info');
}

function processQRManual() {
  const value = document.getElementById('qrManualInput').value.trim();
  if (!value) {
    showToast('Enter QR data', 'error');
    return;
  }
  processQRData(value);
  document.getElementById('qrManualInput').value = '';
}

async function processQRData(data) {
  const rawData = String(data || '').trim();
  if (!rawData) {
    showToast('Invalid QR code', 'error');
    return;
  }

  const now = new Date();
  const dateInput = document.getElementById('qrDate');
  const date = dateInput && dateInput.value ? dateInput.value : qrLocalDate(now);
  const invalidLockKey = `${date}|${rawData}`;

  const parsedQr = parseStudentQrPayload(rawData);
  if (!parsedQr) {
    if (qrInvalidLocks.has(invalidLockKey)) return;
    qrInvalidLocks.add(invalidLockKey);
    appendQrLog({
      ok: false,
      name: 'Invalid QR',
      studentId: '-',
      time: qrLocalTime(now),
      status: 'Invalid'
    });
    showToast('Invalid QR code. Please scan a valid staff QR code only.', 'error');
    return;
  }

  const subjectSelect = document.getElementById('qrSubject') || document.getElementById('attSubject');
  const subjectId = subjectSelect && subjectSelect.value ? subjectSelect.value : '';
  const timeIn = qrLocalTime(now);
  const dailyLockKey = `${parsedQr.studentNumber}|${date}`;

  if (qrDailyLocks.has(dailyLockKey) || qrProcessingLocks.has(dailyLockKey)) {
    return;
  }
  qrProcessingLocks.add(dailyLockKey);

  const livePosition = typeof captureLiveAttendancePosition === 'function'
    ? await captureLiveAttendancePosition(5000)
    : null;
  const locationAddress = livePosition && typeof reverseGeocodeAddress === 'function'
    ? await reverseGeocodeAddress(livePosition.latitude, livePosition.longitude)
    : '';

  const res = await API.post('/api/qr', {
    qr_data: rawData,
    subject_id: subjectId,
    date,
    time_in: timeIn,
    latitude: livePosition ? livePosition.latitude : '',
    longitude: livePosition ? livePosition.longitude : '',
    location_address: locationAddress
  });

  const studentNumber = res.student_id || parsedQr.studentNumber || '-';
  const name = res.name || 'Staff';
  const ok = !!res.message;
  const duplicate = !ok && String(res.error || '').toLowerCase().includes('already been scanned');
  qrProcessingLocks.delete(dailyLockKey);
  if (duplicate && qrDailyLocks.has(dailyLockKey)) return;
  appendQrLog({
    ok,
    name: ok ? name : (duplicate ? name : 'Invalid QR'),
    studentId: studentNumber,
    workArea: res.subject_name || res.work_area || '',
    time: res.time_in || timeIn,
    status: ok ? 'Saved' : (duplicate ? 'Duplicate' : (res.error || 'Error'))
  });

  if (ok || duplicate) {
    qrDailyLocks.add(dailyLockKey);
  }

  if (ok && typeof loadRecords === 'function') {
    const recDate = document.getElementById('recDate');
    if (recDate) recDate.value = res.date || date;
    loadRecords();
  }

  if (ok && typeof refreshGeoTrackingMap === 'function') {
    refreshGeoTrackingMap();
  }

  showToast(ok ? `${name} marked present` : (res.error ? `Error: ${res.error}` : 'QR scan failed'), ok ? 'success' : 'error');
}

function appendQrLog({ ok, name, studentId, workArea, time, status }) {
  const log = document.getElementById('qrLog');
  const entry = document.createElement('div');
  entry.className = 'qr-log-entry ' + (ok ? 'success' : 'error');
  entry.dataset.scanStatus = ok ? 'success' : 'error';
  entry.innerHTML = `<span class="qr-log-main"><span class="qr-log-name">${name || 'Staff'}</span><span class="qr-log-workarea">${workArea || 'Work area pending'}</span></span>
    <span class="qr-log-id">${studentId || '-'}</span>
    <span class="qr-log-time">${time || '-'}</span>
    <span class="qr-log-status">${status || (ok ? 'Saved' : 'Error')}</span>`;

  if (log.querySelector('.empty-msg')) log.innerHTML = '';
  log.prepend(entry);
  filterQrLog();
}

function filterQrLog() {
  const log = document.getElementById('qrLog');
  if (!log) return;
  const q = (document.getElementById('qrLogSearch')?.value || '').toLowerCase();
  const status = document.getElementById('qrLogStatusFilter')?.value || '';
  let visible = 0;
  log.querySelectorAll('.qr-log-entry').forEach(entry => {
    const matchesSearch = entry.textContent.toLowerCase().includes(q);
    const matchesStatus = !status || entry.dataset.scanStatus === status;
    const show = matchesSearch && matchesStatus;
    entry.style.display = show ? '' : 'none';
    if (show) visible++;
  });
  const empty = log.querySelector('.empty-msg');
  if (empty) empty.style.display = visible ? 'none' : '';
}
