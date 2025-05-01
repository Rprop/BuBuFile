const I18N = {
  en: {
    tagline: 'LAN share & upload',
    port: 'Port',
    shared_title: 'Shared files',
    shared_hint: 'Files currently available from this phone',
    shared_empty: 'No shared files yet',
    share_remove: 'Remove',
    share_remove_confirm: 'Stop sharing this file?',
    share_removed: 'Share removed',
    clip_title: 'Clipboard',
    clip_hint: 'Keep BuBuFile in the foreground on the phone, then read or set clipboard text here.',
    clip_placeholder: 'Text to set on phone',
    clip_phone_empty: 'Phone clipboard is empty',
    clip_read: 'Read from phone',
    clip_set: 'Set on phone',
    clip_reading: 'Reading phone clipboard…',
    clip_loaded: 'Loaded from phone',
    clip_setting: 'Setting phone clipboard…',
    clip_updated: 'Clipboard updated',
    upload_title: 'Upload to phone',
    upload_hint: 'Choose files, paste with Ctrl+V / ⌘V, or drop them below. Hover an image to preview.',
    add_file: '+ Add file',
    submit: 'Submit',
    mode_share: 'Share To Apps',
    mode_share_desc: 'Open the system share sheet',
    mode_download: 'Upload Files To Download',
    mode_download_desc: 'Save into the Download folder',
    mode_install: 'Install Apk',
    mode_install_desc: 'Launch the package installer',
    no_file: 'No file chosen',
    select_mode: 'Select an upload mode',
    no_files: 'No file selected',
    uploading: 'Uploading',
    upload_done: 'Upload done',
    upload_retry: 'Retrying…',
    theme_light: 'Light',
    theme_dark: 'Dark'
  },
  zh: {
    tagline: '局域网分享与上传',
    port: '端口',
    shared_title: '已分享文件',
    shared_hint: '当前手机上可供下载的文件',
    shared_empty: '暂无已分享文件',
    share_remove: '移除',
    share_remove_confirm: '停止分享该文件？',
    share_removed: '已停止分享',
    clip_title: '剪切板',
    clip_hint: '请将 BuBuFile 保持在手机前台，然后在此读取或设置剪切板文本。',
    clip_placeholder: '要设置到手机的文本',
    clip_phone_empty: '手机剪切板为空',
    clip_read: '从手机读取',
    clip_set: '设置到手机',
    clip_reading: '正在读取手机剪切板…',
    clip_loaded: '已从手机读取',
    clip_setting: '正在设置手机剪切板…',
    clip_updated: '剪切板已更新',
    upload_title: '上传到手机',
    upload_hint: '可选择文件、使用 Ctrl+V / ⌘V 粘贴，或拖放到下方区域。悬停图片可预览。',
    add_file: '+ 添加文件',
    submit: '提交',
    mode_share: '分享到应用',
    mode_share_desc: '打开系统分享面板',
    mode_download: '上传到 Download',
    mode_download_desc: '保存到系统 Download 目录',
    mode_install: '安装 Apk',
    mode_install_desc: '调起安装程序',
    no_file: '未选择文件',
    select_mode: '请选择上传方式',
    no_files: '未选择文件',
    uploading: '正在上传',
    upload_done: '上传完成',
    upload_retry: '重试中…',
    theme_light: '浅色',
    theme_dark: '深色'
  }
};

let lang = localStorage.getItem('bubufile_lang')
  || (((navigator.language || '').toLowerCase().indexOf('zh') === 0) ? 'zh' : 'en');

const THEME_KEY = 'bubufile_theme';
let theme = (() => {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch (_) {}
  return 'light';
})();
document.documentElement.setAttribute('data-theme', theme);

function t(key) {
  return (I18N[lang] && I18N[lang][key]) || I18N.en[key] || key;
}

function applyTheme(next) {
  if (next === 'light' || next === 'dark') {
    theme = next;
    try {
      localStorage.setItem(THEME_KEY, theme);
    } catch (_) {}
  }
  document.documentElement.setAttribute('data-theme', theme);
  document.querySelectorAll('.theme-switch button').forEach(btn => {
    btn.classList.toggle('active', btn.getAttribute('data-theme-set') === theme);
  });
}

function applyLang(next) {
  if (next) {
    lang = next;
    localStorage.setItem('bubufile_lang', lang);
  }
  document.documentElement.lang = (lang === 'zh') ? 'zh-CN' : 'en';
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const key = el.getAttribute('data-i18n');
    if (key) el.textContent = t(key);
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
    el.placeholder = t(el.getAttribute('data-i18n-placeholder'));
  });
  document.querySelectorAll('[data-i18n-empty]').forEach(el => {
    el.setAttribute('data-empty', t(el.getAttribute('data-i18n-empty')));
  });
  document.querySelectorAll('.file-name:not(.has)').forEach(el => {
    el.textContent = t('no_file');
  });
  document.querySelectorAll('.lang-switch button').forEach(btn => {
    btn.classList.toggle('active', btn.getAttribute('data-lang') === lang);
  });
}

const TAB_KEY = 'bubufile_tab';
const TAB_IDS = ['shared', 'upload', 'clipboard'];

function switchTab(tabId) {
  const id = TAB_IDS.indexOf(tabId) >= 0 ? tabId : 'shared';
  document.querySelectorAll('.tab').forEach(btn => {
    const selected = btn.getAttribute('data-tab') === id;
    btn.setAttribute('aria-selected', selected ? 'true' : 'false');
  });
  document.querySelectorAll('.tab-panel').forEach(panel => {
    panel.hidden = panel.getAttribute('data-panel') !== id;
  });
  try {
    localStorage.setItem(TAB_KEY, id);
  } catch (_) {}
}

function setupTabs() {
  document.querySelectorAll('.tab').forEach(btn => {
    btn.addEventListener('click', () => switchTab(btn.getAttribute('data-tab')));
  });
  let initial = 'shared';
  try {
    initial = localStorage.getItem(TAB_KEY) || 'shared';
  } catch (_) {}
  switchTab(initial);
}

let previewTip = null, previewUrl = null;

function ensurePreviewTip() {
  if (previewTip) return;
  previewTip = document.createElement('div');
  previewTip.id = 'preview-tip';
  document.body.appendChild(previewTip);
}

function addPreview(input) {
  ensurePreviewTip();
  input.addEventListener('mouseenter', () => {
    const file = input.files[0];
    if (!(file && file.type && file.type.startsWith('image/'))) return;
    previewUrl = URL.createObjectURL(file);
    const img = document.createElement('img');
    img.src = previewUrl;
    const r = input.getBoundingClientRect();
    previewTip.innerHTML = '';
    previewTip.appendChild(img);
    previewTip.style.left = Math.min(r.left, window.innerWidth - 380) + 'px';
    previewTip.style.top = (r.bottom + 8) + 'px';
    previewTip.style.display = 'block';
  });
  input.addEventListener('mouseleave', () => {
    if (previewTip) previewTip.style.display = 'none';
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
      previewUrl = null;
    }
  });
}

function formatFileSize(bytes) {
  if (!bytes || bytes < 1024) return (bytes || 0) + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function updateRowLabel(input) {
  const label = input.parentElement && input.parentElement.querySelector('.file-name');
  if (!label) return;
  const file = input.files[0];
  if (file) {
    label.textContent = formatFileSize(file.size);
    label.classList.add('has');
  } else {
    label.textContent = t('no_file');
    label.classList.remove('has');
  }
}

function addFile() {
  const container = document.getElementById('ff');
  const count = container.querySelectorAll('input[type="file"]').length;
  const row = document.createElement('div');
  row.className = 'file-row';
  const input = document.createElement('input');
  input.type = 'file';
  input.name = 'f' + count;
  const label = document.createElement('div');
  label.className = 'file-name';
  label.textContent = t('no_file');
  input.addEventListener('change', () => {
    updateRowLabel(input);
    if (!input.dataset.previewBound) {
      addPreview(input);
      input.dataset.previewBound = '1';
    }
    const empty = [...container.querySelectorAll('input[type="file"]')]
      .some(i => !i.files || i.files.length === 0);
    if (input.files && input.files.length && !empty) addFile();
  });
  row.appendChild(input);
  row.appendChild(label);
  container.appendChild(row);
}

function addFileWithBlob(file) {
  const container = document.getElementById('ff');
  let input = Array.from(container.querySelectorAll('input[type="file"]'))
    .find(i => i.files.length === 0);
  if (!input) {
    addFile();
    input = Array.from(container.querySelectorAll('input[type="file"]'))
      .find(i => i.files.length === 0);
  }
  try {
    const dt = new DataTransfer();
    dt.items.add(file);
    input.files = dt.files;
    input.dispatchEvent(new Event('change'));
  } catch (e) {
    alert('Error: ' + e);
  }
}

function addPasteListener() {
  document.addEventListener('paste', e => {
    const cd = e.clipboardData || window.clipboardData;
    if (!cd) return;
    let added = 0;
    const items = cd.items || [];
    for (const item of items) {
      if (item.kind === 'file') {
        const f = item.getAsFile();
        if (f) {
          addFileWithBlob(f);
          ++added;
        }
      }
    }
    if (added === 0 && cd.files) {
      for (const f of cd.files) {
        addFileWithBlob(f);
        ++added;
      }
    }
    if (added) e.preventDefault();
  });
}

function setupDropZone() {
  const drop = document.getElementById('drop');
  if (!drop) return;
  ['dragenter', 'dragover'].forEach(type => drop.addEventListener(type, e => {
    e.preventDefault();
    drop.classList.add('dragover');
  }));
  ['dragleave', 'drop'].forEach(type => drop.addEventListener(type, e => {
    e.preventDefault();
    drop.classList.remove('dragover');
  }));
  drop.addEventListener('drop', e => {
    const files = e.dataTransfer && e.dataTransfer.files;
    if (!files) return;
    for (const f of files) addFileWithBlob(f);
  });
}

const UPLOAD_CHUNK = 2 * 1024 * 1024;
const UPLOAD_BATCH_KEY = 'bubufile_upload_batch';

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function fmtPct(done, total) {
  if (!total) return '100%';
  return Math.min(100, Math.floor(done * 100 / total)) + '%';
}

function fileFingerprint(file) {
  return file.name + '|' + file.size + '|' + (file.lastModified || 0);
}

function uploadFingerprint(mode, files) {
  return mode + '::' + files.map(fileFingerprint).join('||');
}

function loadPersistedBatch(fingerprint) {
  try {
    const raw = localStorage.getItem(UPLOAD_BATCH_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw);
    if (data && data.fingerprint === fingerprint && data.batch) return String(data.batch);
  } catch (_) {}
  return null;
}

function savePersistedBatch(fingerprint, batch) {
  try {
    localStorage.setItem(UPLOAD_BATCH_KEY, JSON.stringify({
      fingerprint: fingerprint,
      batch: batch,
      savedAt: Date.now()
    }));
  } catch (_) {}
}

function clearPersistedBatch() {
  try {
    localStorage.removeItem(UPLOAD_BATCH_KEY);
  } catch (_) {}
}

function newUploadBatchId() {
  return (crypto.randomUUID && crypto.randomUUID()) || ('b' + Date.now());
}

async function queryUploadReceived(batch, file) {
  const res = await fetch('/upload/status', {
    headers: {
      'X-Upload-Batch': batch,
      'X-File-Name': encodeURIComponent(file.name),
      'X-File-Size': String(file.size)
    }
  });
  const data = await res.json();
  if (!res.ok || data.ok === false) throw new Error(data.message || ('HTTP ' + res.status));
  return Number(data.received) || 0;
}

async function uploadFileResumable(file, mode, batch, count, status, index, total) {
  let offset = await queryUploadReceived(batch, file);
  let lastMessage = null;
  let finalizeAttempt = false;
  while (true) {
    if (offset > file.size) throw new Error('upload overflow');
    if (offset >= file.size) {
      if (finalizeAttempt) throw new Error('upload stalled');
      finalizeAttempt = true;
    }
    const end = Math.min(offset + UPLOAD_CHUNK, file.size);
    const blob = file.slice(offset, end);
    let attempt = 0;
    while (true) {
      attempt++;
      if (status) {
        status.textContent = t('uploading') + ' ' + index + '/' + total + ': '
          + file.name + ' ' + fmtPct(offset, file.size)
          + (attempt > 1 ? (' (' + t('upload_retry') + ')') : '');
      }
      try {
        const res = await fetch('/upload', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/octet-stream',
            'X-Lan-Mode': mode,
            'X-File-Name': encodeURIComponent(file.name),
            'X-Upload-Batch': batch,
            'X-Upload-Count': String(count),
            'X-File-Size': String(file.size),
            'X-Chunk-Offset': String(offset)
          },
          body: blob
        });
        const text = await res.text();
        let data;
        try {
          data = JSON.parse(text);
        } catch (_) {
          throw new Error(text || ('HTTP ' + res.status));
        }
        if (!res.ok || data.ok === false) {
          if (typeof data.received === 'number') {
            offset = data.received;
            finalizeAttempt = false;
            if (res.status === 409) break;
          }
          throw new Error(data.message || ('HTTP ' + res.status));
        }
        offset = Number(data.received) || end;
        if (data.message) lastMessage = data.message;
        if (data.batchComplete) return lastMessage || t('upload_done');
        if (data.fileComplete) return lastMessage;
        break;
      } catch (err) {
        if (attempt >= 5) throw err;
        await sleep(400 * attempt);
        offset = await queryUploadReceived(batch, file);
        finalizeAttempt = false;
      }
    }
  }
}

async function removeShare(btn) {
  const li = btn.closest('.file-item');
  const link = li && li.querySelector('a[href]');
  if (!link) return;
  if (!confirm(t('share_remove_confirm'))) return;
  btn.disabled = true;
  try {
    const res = await fetch(link.getAttribute('href'), { method: 'DELETE' });
    const data = await res.json();
    if (!res.ok || data.ok === false) throw new Error(data.message || ('HTTP ' + res.status));
    const list = li.parentElement;
    li.remove();
    if (list && list.classList.contains('file-list') && !list.children.length) {
      const empty = document.createElement('p');
      empty.className = 'empty';
      empty.setAttribute('data-i18n', 'shared_empty');
      empty.textContent = t('shared_empty');
      list.replaceWith(empty);
    }
    showFlash(t('share_removed'), false);
  } catch (err) {
    showFlash(err.message || String(err), true);
    btn.disabled = false;
  }
}

function showFlash(message, isError) {
  let el = document.getElementById('flash');
  if (!el) {
    el = document.createElement('div');
    el.id = 'flash';
    const wrap = document.querySelector('.wrap');
    const hero = wrap && wrap.querySelector('.hero');
    if (wrap && hero) wrap.insertBefore(el, hero.nextSibling);
    else if (wrap) wrap.insertBefore(el, wrap.firstChild);
    else return;
  }
  el.className = 'flash ' + (isError ? 'err' : 'ok');
  el.textContent = message || '';
  el.style.display = message ? '' : 'none';
  if (message) el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function setupForm() {
  const form = document.getElementById('upload-form');
  if (!form) return;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const modeEl = form.querySelector('input[name=t]:checked');
    if (!modeEl) {
      alert(t('select_mode'));
      return;
    }
    const files = [...form.querySelectorAll('#ff input[type=file]')]
      .map(i => i.files[0]).filter(Boolean);
    if (!files.length) {
      alert(t('no_files'));
      return;
    }
    const status = document.getElementById('upload-status');
    const btn = form.querySelector('button[type=submit]');
    btn.disabled = true;
    showFlash('', false);
    const fingerprint = uploadFingerprint(modeEl.value, files);
    let batch = loadPersistedBatch(fingerprint) || newUploadBatchId();
    savePersistedBatch(fingerprint, batch);
    try {
      let message = t('upload_done');
      for (let i = 0; i < files.length; i++) {
        const done = await uploadFileResumable(
          files[i], modeEl.value, batch, files.length, status, i + 1, files.length);
        if (done) message = done;
      }
      clearPersistedBatch();
      if (status) status.textContent = message;
      showFlash(message, false);
    } catch (err) {
      const message = err.message || String(err);
      if (status) status.textContent = message;
      showFlash(message, true);
    } finally {
      btn.disabled = false;
    }
  });
}

async function loadClipboard() {
  const status = document.getElementById('clipboard-status');
  const out = document.getElementById('clipboard-out');
  if (status) status.textContent = t('clip_reading');
  try {
    const res = await fetch('/clipboard');
    const data = await res.json();
    if (!res.ok || data.ok === false) throw new Error(data.message || ('HTTP ' + res.status));
    out.textContent = data.text || '';
    if (status) status.textContent = t('clip_loaded');
  } catch (err) {
    if (status) status.textContent = err.message || String(err);
  }
}

async function setClipboard() {
  const status = document.getElementById('clipboard-status');
  const box = document.getElementById('clipboard-text');
  const text = box.value || '';
  if (status) status.textContent = t('clip_setting');
  try {
    const body = new TextEncoder().encode(text);
    const res = await fetch('/clipboard', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body
    });
    const data = await res.json();
    if (!res.ok || data.ok === false) throw new Error(data.message || ('HTTP ' + res.status));
    if (status) status.textContent = t('clip_updated');
  } catch (err) {
    if (status) status.textContent = err.message || String(err);
  }
}

window.onload = () => {
  applyLang();
  applyTheme();
  setupTabs();
  addPasteListener();
  setupDropZone();
  setupForm();
  addFile();
};
