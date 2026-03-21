/* webshare.js */
document.addEventListener('DOMContentLoaded', function() {

  /* ── Read server-injected data ── */
  var wsDataEl = document.getElementById('ws-data');
  if (!wsDataEl) { console.error('ws-data missing'); return; }

  var _d            = JSON.parse(wsDataEl.textContent || '{}');
  var uploadAllowed = _d.uploadAllowed === true;
  var files         = (_d.files || []).map(mapFile);
  var dark          = true;

  /* Last known version from server — -1 means "not yet polled" */
  var lastVersion   = -1;

  function mapFile(f) {
    return { name: String(f.name || ''), size: Number(f.size) || 0 };
  }

  /* ── Theme ── */
  if (localStorage.getItem('wsTheme') === 'light') {
    dark = false;
    document.body.setAttribute('data-theme', 'light');
    document.getElementById('themeBtn').textContent = '\u263E';
  }

  /* ── Upload button ── */
  function setUploadVisible(v) {
    document.getElementById('uploadBtn').classList.toggle('hidden', !v);
  }
  setUploadVisible(uploadAllowed);

  /* ── Polling — GET /events returns {v, upload, exec} ──────────────────
     /events is a tiny instant JSON response — no held connections.
     We poll every 1.5s. On version change → fetch /api/files and re-render.
  ─────────────────────────────────────────────────────────────────────── */
  var pollTimer = null;

  function poll() {
    fetch('/events', { credentials: 'same-origin', cache: 'no-store' })
      .then(function(r) {
        if (r.status === 403) { window.location.href = '/'; return null; }
        if (!r.ok) return null;
        return r.json();
      })
      .then(function(state) {
        if (!state) return;

        var serverVer = state.v;

        if (lastVersion === -1) {
          /* First poll — just record baseline, don't refresh yet */
          lastVersion   = serverVer;
          uploadAllowed = state.upload === true;
          setUploadVisible(uploadAllowed);
          return;
        }

        if (serverVer !== lastVersion) {
          /* Something changed — check what */
          var uploadChanged = (state.upload === true) !== uploadAllowed;

          lastVersion   = serverVer;
          uploadAllowed = state.upload === true;

          if (uploadChanged) {
            setUploadVisible(uploadAllowed);
            toast(uploadAllowed
              ? '\u2713 Upload enabled by host'
              : '\u26a0 Upload disabled by host');
          }

          /* Always refresh file list on any version change */
          refreshFiles();
        }
      })
      .catch(function() { /* network blip — retry */ });
  }

  function startPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(poll, 1500);
    poll(); /* immediate first check */
  }

  function stopPolling() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  startPolling();

  /* Pause when tab hidden — save battery and bandwidth */
  document.addEventListener('visibilitychange', function() {
    if (document.hidden) stopPolling();
    else startPolling();
  });

  /* ── Fetch fresh file list ── */
  function refreshFiles() {
    fetch('/api/files', { credentials: 'same-origin', cache: 'no-store' })
      .then(function(r) {
        if (r.status === 403) { window.location.href = '/'; return null; }
        if (!r.ok) return null;
        return r.json();
      })
      .then(function(data) {
        if (!data) return;
        var prev  = files.length;
        files     = data.map(mapFile);
        render();
        var diff = files.length - prev;
        if (diff > 0)      toast('\u2B06 ' + diff + ' file' + (diff > 1 ? 's' : '') + ' added');
        else if (diff < 0) toast('\u2713 File list updated');
      })
      .catch(function() {});
  }

  /* ── Helpers ── */
  function fmtSize(b) {
    if (b < 1024)         return b + ' B';
    if (b < 1048576)      return (b / 1024).toFixed(1) + ' KB';
    if (b < 1073741824)   return (b / 1048576).toFixed(1) + ' MB';
    return (b / 1073741824).toFixed(1) + ' GB';
  }

  function getExt(n) {
    var e = n.split('.').pop().toUpperCase();
    return e.length > 5 ? e.slice(0, 5) : (e || 'FILE');
  }

  function extCls(n) {
    var e = n.split('.').pop().toLowerCase();
    return ({
      pdf:'ext-pdf', zip:'ext-zip', rar:'ext-rar',
      xlsx:'ext-xlsx', xls:'ext-xls', csv:'ext-csv',
      mp3:'ext-mp3', wav:'ext-wav', flac:'ext-flac',
      html:'ext-html', htm:'ext-htm', css:'ext-css', js:'ext-js',
      png:'ext-png', jpg:'ext-jpg', jpeg:'ext-jpeg',
      gif:'ext-gif', webp:'ext-webp'
    })[e] || 'ext-default';
  }

  function esc(s) {
    return String(s)
      .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
      .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
  }

  function dlSvg() {
    return '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
      + ' stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">'
      + '<path d="M12 3v13M7 11l5 5 5-5M3 20h18"></path></svg>';
  }

  function toast(msg, err) {
    var el = document.getElementById('toast');
    el.textContent = msg;
    el.className = 'toast show' + (err ? ' err' : '');
    clearTimeout(el._t);
    el._t = setTimeout(function() { el.className = 'toast'; }, 3500);
  }

  /* ── Download via fetch+blob so session cookie is always sent ── */
  function doDownload(filename) {
    toast('\u2193 ' + filename);
    fetch('/files/' + encodeURIComponent(filename), { credentials: 'same-origin' })
      .then(function(r) {
        if (r.status === 403) {
          toast('Session expired \u2014 redirecting...', true);
          setTimeout(function() { window.location.href = '/'; }, 1500);
          return null;
        }
        if (!r.ok) { toast('Download failed: ' + r.status, true); return null; }
        return r.blob();
      })
      .then(function(blob) {
        if (!blob) return;
        var a   = document.createElement('a');
        var obj = URL.createObjectURL(blob);
        a.href = obj; a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function() { URL.revokeObjectURL(obj); }, 10000);
        toast('\u2713 ' + filename + ' saved');
      })
      .catch(function() { toast('Network error', true); });
  }

  /* ── Render ── */
  function render() {
    var wrap  = document.getElementById('wrap');
    var badge = document.getElementById('cntBadge');
    badge.textContent = files.length + (files.length === 1 ? ' file' : ' files');

    if (!files.length) {
      wrap.innerHTML =
        '<div class="empty">'
        + '<span class="empty-icon">&#128193;</span>'
        + '<div class="empty-text">NO FILES SHARED YET</div>'
        + '</div>';
      return;
    }

    var h = '<div class="file-list">';
    files.forEach(function(f) {
      h += '<div class="file-item">'
        + '<span class="ext-badge ' + extCls(f.name) + '">' + esc(getExt(f.name)) + '</span>'
        + '<div class="file-info">'
        + '<div class="file-name">' + esc(f.name) + '</div>'
        + '<div class="file-meta">' + fmtSize(f.size) + '</div>'
        + '</div>'
        + '<button class="dl-btn" data-name="' + esc(f.name) + '">'
        + dlSvg() + '<span>DOWNLOAD</span>'
        + '</button>'
        + '</div>';
    });
    h += '</div>';
    wrap.innerHTML = h;

    document.querySelectorAll('.dl-btn').forEach(function(btn) {
      btn.addEventListener('click', function() { doDownload(this.dataset.name); });
    });
  }

  /* ── Upload ── */
  window.doUpload = function(fileList) {
    if (!fileList || !fileList.length) return;
    if (!uploadAllowed) { toast('Upload not permitted by host', true); return; }

    var MAX = 500 * 1024 * 1024;
    for (var i = 0; i < fileList.length; i++) {
      if (fileList[i].size > MAX) {
        toast('File too large: ' + fileList[i].name, true);
        document.getElementById('fileInput').value = '';
        return;
      }
    }

    var total = fileList.length, done = 0, ok = 0;
    var bar  = document.getElementById('uploadBar');
    var fill = document.getElementById('uploadFill');
    bar.classList.add('active');
    fill.style.width = '0%';

    Array.prototype.forEach.call(fileList, function(file) {
      var fd = new FormData();
      fd.append('file', file, file.name);
      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/upload', true);
      xhr.withCredentials = true;
      xhr.upload.onprogress = function(e) {
        if (e.lengthComputable)
          fill.style.width =
            Math.round(((done + e.loaded / e.total) / total) * 100) + '%';
      };
      xhr.onload = function() {
        done++;
        if (xhr.status === 200) ok++;
        else if (xhr.status === 403) { window.location.href = '/'; return; }
        if (done === total) {
          fill.style.width = '100%';
          setTimeout(function() {
            bar.classList.remove('active');
            fill.style.width = '0%';
          }, 800);
          if (ok > 0) {
            toast('\u2713 ' + ok + ' file' + (ok > 1 ? 's' : '') + ' uploaded');
            /* Polling will detect version change and refresh automatically */
            /* But also refresh immediately so uploader sees it right away */
            refreshFiles();
          } else {
            toast('Upload failed', true);
          }
        }
      };
      xhr.onerror = function() {
        done++;
        if (done === total) toast('Network error', true);
      };
      xhr.send(fd);
    });

    document.getElementById('fileInput').value = '';
  };

  /* ── Theme ── */
  window.toggleTheme = function() {
    dark = !dark;
    document.body.setAttribute('data-theme', dark ? '' : 'light');
    document.getElementById('themeBtn').textContent = dark ? '\u2600' : '\u263E';
    localStorage.setItem('wsTheme', dark ? 'dark' : 'light');
  };

  render();

}); /* end DOMContentLoaded */