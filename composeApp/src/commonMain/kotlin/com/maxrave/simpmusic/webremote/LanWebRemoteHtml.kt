package com.maxrave.simpmusic.webremote

/**
 * Self-contained dark-mode mobile-responsive single-page HTML5+CSS+JS remote control UI.
 * Zero external CDN dependencies so it renders instantly over local Wi-Fi.
 */
object LanWebRemoteHtml {
    fun escapeHtml(raw: String): String =
        buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }

    fun renderPage(deviceLabel: String = "SimpMusic"): String {
        val safeTitle = escapeHtml(deviceLabel.ifBlank { "SimpMusic" })
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
<meta name="theme-color" content="#000000" />
<title>$safeTitle — LAN Remote</title>
<style>
:root {
  --bg: #000000;
  --surface: #141416;
  --elevated: #1c1c1e;
  --border: rgba(255,255,255,0.08);
  --text: #ffffff;
  --muted: #a1a1aa;
  --accent: #fa233b;
  --accent-soft: rgba(250,35,59,0.18);
  --ok: #30d158;
}
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body {
  margin: 0; padding: 0;
  background: var(--bg); color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
  min-height: 100%;
}
.shell {
  max-width: 480px; margin: 0 auto;
  padding: 16px 16px 40px;
  display: flex; flex-direction: column; gap: 16px;
}
.topbar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.brand { display: flex; align-items: center; gap: 10px; }
.dot { width: 9px; height: 9px; border-radius: 50%; background: var(--ok); box-shadow: 0 0 10px var(--ok); }
.dot.offline { background: var(--accent); box-shadow: none; }
.brand h1 { margin: 0; font-size: 16px; font-weight: 700; letter-spacing: -0.02em; }
.brand span { font-size: 12px; color: var(--muted); display: block; }
.pill-btn {
  border: 1px solid var(--border); background: var(--elevated); color: var(--text);
  border-radius: 999px; padding: 7px 12px; font-size: 12px; font-weight: 600; cursor: pointer;
}
.card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 22px; padding: 18px;
}
.art-wrap {
  width: 100%; aspect-ratio: 1 / 1; max-height: 300px; margin: 0 auto 16px;
  border-radius: 16px; overflow: hidden; background: linear-gradient(145deg, #1f1f23, #0c0c0e);
  display: flex; align-items: center; justify-content: center; position: relative;
}
.art-wrap img {
  width: 100%; height: 100%; object-fit: cover; display: none;
}
.art-fallback { font-size: 56px; color: rgba(255,255,255,0.22); user-select: none; }
.track-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.track-meta { min-width: 0; flex: 1; }
.track-title {
  margin: 0; font-size: 20px; font-weight: 700; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}
.track-artist {
  margin: 4px 0 0; font-size: 14px; color: var(--muted); white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}
.icon-btn {
  border: none; background: var(--elevated); color: var(--text);
  width: 44px; height: 44px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 18px; cursor: pointer; transition: transform 0.12s ease, background 0.15s ease;
}
.icon-btn:active { transform: scale(0.93); }
.icon-btn.active { background: var(--accent-soft); color: var(--accent); }
.scrubber { margin-top: 16px; }
input[type="range"] {
  -webkit-appearance: none; appearance: none; width: 100%; height: 6px;
  border-radius: 999px; background: #2c2c2e; outline: none; cursor: pointer;
}
input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none; appearance: none; width: 16px; height: 16px;
  border-radius: 50%; background: var(--accent); border: 2px solid #ffffff;
}
.times {
  display: flex; justify-content: space-between; font-size: 12px;
  color: var(--muted); margin-top: 6px; font-variant-numeric: tabular-nums;
}
.controls {
  margin-top: 16px; display: flex; align-items: center; justify-content: space-between; gap: 8px;
}
.play-btn {
  width: 64px; height: 64px; border-radius: 50%; border: none;
  background: var(--accent); color: #ffffff; font-size: 26px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: 0 8px 24px rgba(250,35,59,0.38); transition: transform 0.12s ease;
}
.play-btn:active { transform: scale(0.94); }
.volume-row {
  margin-top: 18px; display: flex; align-items: center; gap: 10px;
}
.volume-row span { font-size: 13px; color: var(--muted); min-width: 38px; text-align: right; font-variant-numeric: tabular-nums; }
.section-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 10px;
}
.section-head h2 { margin: 0; font-size: 15px; font-weight: 700; }
.section-head span { font-size: 12px; color: var(--muted); }
.queue-list { display: flex; flex-direction: column; gap: 6px; max-height: 320px; overflow-y: auto; }
.queue-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border-radius: 14px; background: var(--elevated); border: 1px solid transparent;
  cursor: pointer; text-align: left; width: 100%; color: var(--text);
}
.queue-item.current { border-color: var(--accent); background: var(--accent-soft); }
.queue-idx { width: 22px; font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.queue-text { min-width: 0; flex: 1; }
.queue-title { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.queue-artist { font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.empty { text-align: center; color: var(--muted); font-size: 13px; padding: 18px 8px; }
.pin-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.88); backdrop-filter: blur(14px);
  display: flex; align-items: center; justify-content: center; padding: 20px; z-index: 20;
}
.pin-overlay.hidden { display: none; }
.pin-card {
  width: 100%; max-width: 340px; background: var(--surface);
  border: 1px solid var(--border); border-radius: 24px; padding: 24px; text-align: center;
}
.pin-card h2 { margin: 0 0 6px; font-size: 20px; }
.pin-card p { margin: 0 0 18px; font-size: 13px; color: var(--muted); }
.pin-input {
  width: 100%; padding: 14px; border-radius: 14px; border: 1px solid var(--border);
  background: var(--elevated); color: var(--text); font-size: 26px; letter-spacing: 0.45em;
  text-align: center; outline: none; font-variant-numeric: tabular-nums;
}
.pin-input:focus { border-color: var(--accent); }
.pin-submit {
  width: 100%; margin-top: 14px; padding: 13px; border-radius: 14px; border: none;
  background: var(--accent); color: #ffffff; font-size: 15px; font-weight: 700; cursor: pointer;
}
.pin-error { min-height: 18px; margin-top: 10px; font-size: 12px; color: #ff6961; }
</style>
</head>
<body>
<div class="shell">
  <header class="topbar">
    <div class="brand">
      <div id="statusDot" class="dot"></div>
      <div>
        <h1>$safeTitle Remote</h1>
        <span id="statusText">Connecting…</span>
      </div>
    </div>
    <button id="lockBtn" class="pill-btn" type="button">Change PIN</button>
  </header>

  <section class="card">
    <div class="art-wrap">
      <img id="artImg" alt="Album Artwork" />
      <div id="artFallback" class="art-fallback">&#9835;</div>
    </div>
    <div class="track-row">
      <div class="track-meta">
        <p id="trackTitle" class="track-title">Nothing Playing</p>
        <p id="trackArtist" class="track-artist">Start playback in SimpMusic</p>
      </div>
      <button id="likeBtn" class="icon-btn" type="button" title="Like">&#9825;</button>
    </div>

    <div class="scrubber">
      <input id="seekBar" type="range" min="0" max="1000" value="0" />
      <div class="times">
        <span id="posLabel">0:00</span>
        <span id="durLabel">0:00</span>
      </div>
    </div>

    <div class="controls">
      <button id="shuffleBtn" class="icon-btn" type="button" title="Shuffle">&#8646;</button>
      <button id="prevBtn" class="icon-btn" type="button" title="Previous">&#9198;</button>
      <button id="playBtn" class="play-btn" type="button" title="Play / Pause">&#9654;</button>
      <button id="nextBtn" class="icon-btn" type="button" title="Next">&#9197;</button>
      <button id="repeatBtn" class="icon-btn" type="button" title="Repeat">&#128257;</button>
    </div>

    <div class="volume-row">
      <span>&#128266;</span>
      <input id="volBar" type="range" min="0" max="100" value="80" />
      <span id="volLabel">80%</span>
    </div>
  </section>

  <section class="card">
    <div class="section-head">
      <h2>Up Next</h2>
      <span id="queueCount">0 tracks</span>
    </div>
    <div id="queueList" class="queue-list">
      <div class="empty">Queue is empty</div>
    </div>
  </section>
</div>

<div id="pinOverlay" class="pin-overlay hidden">
  <form id="pinForm" class="pin-card">
    <h2>Enter 4-Digit PIN</h2>
    <p>Check SimpMusic Settings &rarr; LAN Web Remote for the PIN.</p>
    <input id="pinInput" class="pin-input" type="password" inputmode="numeric" maxlength="4" pattern="[0-9]{4}" placeholder="••••" autocomplete="off" required />
    <button class="pin-submit" type="submit">Unlock Remote</button>
    <div id="pinError" class="pin-error"></div>
  </form>
</div>

<script>
(function() {
  const PIN_KEY = 'simpmusic_remote_pin';
  let pin = sessionStorage.getItem(PIN_KEY) || '';
  let isSeeking = false;
  let isAdjustingVol = false;
  let durationMs = 0;
  let pollTimer = null;

  const $ = (id) => document.getElementById(id);
  const pinOverlay = $('pinOverlay');
  const pinInput = $('pinInput');
  const pinError = $('pinError');
  const statusDot = $('statusDot');
  const statusText = $('statusText');
  const artImg = $('artImg');
  const artFallback = $('artFallback');
  const trackTitle = $('trackTitle');
  const trackArtist = $('trackArtist');
  const likeBtn = $('likeBtn');
  const seekBar = $('seekBar');
  const posLabel = $('posLabel');
  const durLabel = $('durLabel');
  const shuffleBtn = $('shuffleBtn');
  const prevBtn = $('prevBtn');
  const playBtn = $('playBtn');
  const nextBtn = $('nextBtn');
  const repeatBtn = $('repeatBtn');
  const volBar = $('volBar');
  const volLabel = $('volLabel');
  const queueList = $('queueList');
  const queueCount = $('queueCount');

  function fmtTime(ms) {
    const s = Math.max(0, Math.floor((ms || 0) / 1000));
    const m = Math.floor(s / 60);
    const r = s % 60;
    return m + ':' + String(r).padStart(2, '0');
  }

  function showPinModal(msg) {
    pinError.textContent = msg || '';
    pinOverlay.classList.remove('hidden');
    setTimeout(() => pinInput.focus(), 50);
  }

  function hidePinModal() {
    pinOverlay.classList.add('hidden');
    pinError.textContent = '';
  }

  async function sendCommand(payload) {
    if (!pin) { showPinModal('Enter PIN to control playback'); return; }
    try {
      const res = await fetch('/api/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Remote-Pin': pin },
        body: JSON.stringify(payload)
      });
      if (res.status === 401) {
        sessionStorage.removeItem(PIN_KEY);
        pin = '';
        showPinModal('Incorrect PIN. Please try again.');
        return;
      }
      if (res.ok) fetchState();
    } catch (e) {
      statusDot.classList.add('offline');
      statusText.textContent = 'Connection lost';
    }
  }

  function renderState(s) {
    statusDot.classList.remove('offline');
    statusText.textContent = s.isPlaying ? 'Playing on LAN' : 'Paused';
    trackTitle.textContent = s.title || 'Nothing Playing';
    trackArtist.textContent = s.artist || 'Open SimpMusic to play music';

    if (s.thumbnailUrl) {
      if (artImg.src !== s.thumbnailUrl) artImg.src = s.thumbnailUrl;
      artImg.style.display = 'block';
      artFallback.style.display = 'none';
    } else {
      artImg.style.display = 'none';
      artFallback.style.display = 'block';
    }

    playBtn.innerHTML = s.isPlaying ? '&#10074;&#10074;' : '&#9654;';
    likeBtn.classList.toggle('active', !!s.isLiked);
    likeBtn.innerHTML = s.isLiked ? '&#9829;' : '&#9825;';
    shuffleBtn.classList.toggle('active', !!s.shuffle);
    repeatBtn.classList.toggle('active', s.repeat === 'ALL' || s.repeat === 'ONE');
    repeatBtn.textContent = s.repeat === 'ONE' ? '1' : '\u21BB';

    durationMs = Math.max(0, s.durationMs || 0);
    if (!isSeeking) {
      seekBar.max = String(Math.max(1000, durationMs));
      seekBar.value = String(Math.min(durationMs, Math.max(0, s.positionMs || 0)));
      posLabel.textContent = fmtTime(s.positionMs || 0);
    }
    durLabel.textContent = fmtTime(durationMs);

    if (!isAdjustingVol) {
      const pct = Math.round(Math.max(0, Math.min(1, s.volume ?? 1)) * 100);
      volBar.value = String(pct);
      volLabel.textContent = pct + '%';
    }

    const q = Array.isArray(s.queue) ? s.queue : [];
    queueCount.textContent = q.length + (q.length === 1 ? ' track' : ' tracks');
    queueList.textContent = '';
    if (q.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'Queue is empty';
      queueList.appendChild(empty);
    } else {
      q.forEach((item) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'queue-item' + (item.isCurrent ? ' current' : '');
        btn.addEventListener('click', () => sendCommand({ action: 'jump', index: item.index }));

        const idx = document.createElement('div');
        idx.className = 'queue-idx';
        idx.textContent = item.isCurrent ? '\u25B6' : String(item.index + 1);

        const text = document.createElement('div');
        text.className = 'queue-text';
        const t = document.createElement('div');
        t.className = 'queue-title';
        t.textContent = item.title || 'Unknown Track';
        const a = document.createElement('div');
        a.className = 'queue-artist';
        a.textContent = item.artist || '';
        text.appendChild(t);
        text.appendChild(a);

        btn.appendChild(idx);
        btn.appendChild(text);
        queueList.appendChild(btn);
      });
    }
  }

  async function fetchState() {
    if (!pin || document.hidden) return;
    try {
      const res = await fetch('/api/state', { headers: { 'X-Remote-Pin': pin }, cache: 'no-store' });
      if (res.status === 401) {
        sessionStorage.removeItem(PIN_KEY);
        pin = '';
        showPinModal('Invalid PIN. Check SimpMusic settings.');
        return;
      }
      if (!res.ok) return;
      const data = await res.json();
      hidePinModal();
      renderState(data);
    } catch (e) {
      statusDot.classList.add('offline');
      statusText.textContent = 'Offline — retrying…';
    }
  }

  $('pinForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const candidate = pinInput.value.trim();
    if (!/^\d{4}$/.test(candidate)) {
      pinError.textContent = 'PIN must be 4 digits.';
      return;
    }
    pin = candidate;
    sessionStorage.setItem(PIN_KEY, pin);
    fetchState();
  });

  $('lockBtn').addEventListener('click', () => {
    pinInput.value = '';
    showPinModal('');
  });
  playBtn.addEventListener('click', () => sendCommand({ action: 'play_pause' }));
  nextBtn.addEventListener('click', () => sendCommand({ action: 'next' }));
  prevBtn.addEventListener('click', () => sendCommand({ action: 'previous' }));
  shuffleBtn.addEventListener('click', () => sendCommand({ action: 'shuffle' }));
  repeatBtn.addEventListener('click', () => sendCommand({ action: 'repeat' }));
  likeBtn.addEventListener('click', () => sendCommand({ action: 'like' }));

  seekBar.addEventListener('input', () => {
    isSeeking = true;
    posLabel.textContent = fmtTime(Number(seekBar.value));
  });
  seekBar.addEventListener('change', () => {
    isSeeking = false;
    sendCommand({ action: 'seek', value: Number(seekBar.value) });
  });

  volBar.addEventListener('input', () => {
    isAdjustingVol = true;
    volLabel.textContent = volBar.value + '%';
  });
  volBar.addEventListener('change', () => {
    isAdjustingVol = false;
    sendCommand({ action: 'volume', value: Number(volBar.value) / 100 });
  });

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) fetchState();
  });

  if (!pin) showPinModal('');
  else fetchState();
  pollTimer = setInterval(fetchState, 1000);
})();
</script>
</body>
</html>"""
    }
}
