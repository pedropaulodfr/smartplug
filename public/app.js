'use strict';

const $ = (id) => document.getElementById(id);

const nodes = {
  body: document.body,
  dot: $('status-dot'),
  name: $('device-name'),
  power: $('power'),
  heroState: $('hero-state'),
  powerValue: $('power-value'),
  heroSub: $('hero-sub'),
  voltage: $('voltage'),
  current: $('current'),
  energy: $('energy'),
  latency: $('latency'),
  chart: $('chart'),
  chartEmpty: $('chart-empty'),
  chartRange: $('chart-range'),
  footnote: $('footnote'),
  toast: $('toast'),
  refresh: $('refresh'),
};

const TOKEN_KEY = 'smartplug.token';
let token = localStorage.getItem(TOKEN_KEY) || '';

let state = {
  online: false,
  on: null,
  power: 0,
  voltage: 0,
  current: 0,
  energy: 0,
  latency: null,
  version: null,
  updatedAt: null,
  history: [],
  mock: false,
};

let chartPoints = [];
let lastPointAt = 0;
let busy = false;
let socket = null;
let reconnectDelay = 1000;
let toastTimer = null;

const decimal = (value, digits) => {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—';
  return new Intl.NumberFormat('pt-BR', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(Number(value));
};

function ago(timestamp) {
  if (!timestamp) return '—';
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 5) return 'agora';
  if (seconds < 60) return `há ${seconds}s`;
  const minutes = Math.round(seconds / 60);
  return `há ${minutes}min`;
}

// ---------------------------------------------------------------------
// API
// ---------------------------------------------------------------------
async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body) headers['Content-Type'] = 'application/json';
  if (token) headers['x-token'] = token;

  const response = await fetch(path, { ...options, headers, cache: 'no-store' });

  if (response.status === 401) {
    const provided = window.prompt('Token de acesso (definido no config.json):');
    if (provided) {
      token = provided.trim();
      localStorage.setItem(TOKEN_KEY, token);
      return api(path, options);
    }
    throw new Error('Acesso negado: token inválido.');
  }

  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `Erro HTTP ${response.status}`);
  return data;
}

// ---------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------
function render() {
  const online = Boolean(state.online);
  const on = state.on === true;

  nodes.body.classList.toggle('is-on', online && on);
  nodes.dot.dataset.state = online ? 'online' : 'offline';
  nodes.power.setAttribute('aria-pressed', String(on));
  nodes.power.disabled = false;

  nodes.heroState.textContent = online ? (on ? 'Ligado' : 'Desligado') : 'Offline';

  nodes.powerValue.textContent = online
    ? decimal(state.power, state.power >= 100 ? 0 : 1)
    : '—';

  nodes.heroSub.textContent = !online
    ? 'reconectando ao aparelho…'
    : on
      ? 'consumindo energia agora'
      : 'sem consumo';

  nodes.voltage.textContent = online ? decimal(state.voltage, 1) : '—';
  nodes.current.textContent = online ? decimal(state.current, 2) : '—';
  nodes.energy.textContent = state.energy > 0 ? decimal(state.energy, 3) : '—';
  nodes.latency.textContent = state.latency ? decimal(state.latency, 0) : '—';

  if (state.name) nodes.name.textContent = state.name;

  updateFootnote();
  drawChart();
}

function updateFootnote() {
  const parts = [];
  parts.push(state.updatedAt ? `atualizado ${ago(state.updatedAt)}` : 'sem leituras');
  if (state.via === 'cloud') parts.push('via nuvem');
  if (state.version) parts.push(`protocolo ${state.version}`);
  if (state.mock) parts.push('modo simulado');
  nodes.footnote.textContent = parts.join(' · ');
}

// ---------------------------------------------------------------------
// Gráfico
// ---------------------------------------------------------------------
function pushPoint() {
  const now = Date.now();
  if (!state.online) return;
  if (now - lastPointAt < 1200) return;
  lastPointAt = now;

  chartPoints.push({ t: now, p: Number(state.power) || 0 });
  if (chartPoints.length > 90) chartPoints.splice(0, chartPoints.length - 90);
}

function drawChart() {
  const canvas = nodes.chart;
  const width = canvas.clientWidth || 320;
  const height = canvas.clientHeight || 118;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);

  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);

  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  // Linhas de referência
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
  ctx.lineWidth = 1;
  for (let i = 1; i <= 3; i += 1) {
    const y = (height / 4) * i;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(width, y);
    ctx.stroke();
  }

  if (chartPoints.length < 2) {
    nodes.chartEmpty.hidden = false;
    return;
  }
  nodes.chartEmpty.hidden = true;

  const max = Math.max(50, ...chartPoints.map((point) => point.p)) * 1.25;
  const stepX = width / (chartPoints.length - 1);
  const pointY = (value) => height - 8 - (value / max) * (height - 24);

  const area = ctx.createLinearGradient(0, 0, 0, height);
  area.addColorStop(0, 'rgba(255, 182, 72, 0.26)');
  area.addColorStop(1, 'rgba(255, 182, 72, 0)');

  ctx.beginPath();
  ctx.moveTo(0, height);
  chartPoints.forEach((point, index) => {
    ctx.lineTo(index * stepX, pointY(point.p));
  });
  ctx.lineTo(width, height);
  ctx.closePath();
  ctx.fillStyle = area;
  ctx.fill();

  ctx.beginPath();
  chartPoints.forEach((point, index) => {
    const x = index * stepX;
    const y = pointY(point.p);
    if (index === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.strokeStyle = '#ffb648';
  ctx.lineWidth = 2;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.shadowColor = 'rgba(255, 182, 72, 0.45)';
  ctx.shadowBlur = 12;
  ctx.stroke();
  ctx.shadowBlur = 0;

  const last = chartPoints[chartPoints.length - 1];
  ctx.beginPath();
  ctx.arc(width, pointY(last.p), 3.2, 0, Math.PI * 2);
  ctx.fillStyle = '#ffb648';
  ctx.fill();

  const spanMinutes = Math.max(1, Math.round((Date.now() - chartPoints[0].t) / 60000));
  nodes.chartRange.textContent = `últimos ${spanMinutes} min`;
}

// ---------------------------------------------------------------------
// Estado
// ---------------------------------------------------------------------
function applyState(next) {
  const incomingHistory = Array.isArray(next.history) ? next.history : null;

  state = { ...state, ...next };
  state.online = Boolean(next.online);

  if (incomingHistory && incomingHistory.length) {
    chartPoints = incomingHistory.slice(-90).map((point) => ({ t: point.t, p: point.p }));
    lastPointAt = chartPoints[chartPoints.length - 1]?.t || 0;
  } else {
    pushPoint();
  }

  render();
}

async function loadInfo() {
  try {
    const info = await api('/api/info');
    if (info?.name) nodes.name.textContent = info.name;
    if (info?.mock) state.mock = true;
    document.title = info?.name || 'Smart Plug';
  } catch {
    /* o /api/state ja traz o essencial */
  }
}

async function loadState() {
  try {
    const data = await api('/api/state');
    applyState(data);
  } catch (error) {
    state.online = false;
    render();
    toast(error.message);
  }
}

// ---------------------------------------------------------------------
// Ações
// ---------------------------------------------------------------------
async function toggle() {
  if (busy) return;
  busy = true;
  nodes.power.classList.add('pending');

  const desired = !(state.on === true);
  const optimistic = { ...state, on: desired, power: desired ? state.power : 0, online: state.online };
  applyState(optimistic);

  try {
    const result = await api('/api/power', {
      method: 'POST',
      body: JSON.stringify({ on: desired }),
    });
    if (result?.state) applyState(result.state);
  } catch (error) {
    toast(error.message);
    await loadState();
  } finally {
    busy = false;
    nodes.power.classList.remove('pending');
  }
}

async function refresh() {
  nodes.refresh.classList.add('spinning');
  try {
    await loadState();
  } finally {
    setTimeout(() => nodes.refresh.classList.remove('spinning'), 400);
  }
}

function toast(message) {
  nodes.toast.textContent = message;
  nodes.toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => nodes.toast.classList.remove('show'), 4000);
}

// ---------------------------------------------------------------------
// Tempo real
// ---------------------------------------------------------------------
function connectSocket() {
  if (socket) return;

  const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const query = token ? `?token=${encodeURIComponent(token)}` : '';
  const ws = new WebSocket(`${protocol}://${location.host}/ws${query}`);
  socket = ws;

  ws.addEventListener('open', () => {
    reconnectDelay = 1000;
  });

  ws.addEventListener('message', (event) => {
    try {
      const message = JSON.parse(event.data);
      if (message.type === 'state') applyState(message.state);
    } catch {
      /* ignora payload inválido */
    }
  });

  ws.addEventListener('close', () => {
    socket = null;
    setTimeout(connectSocket, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 1.8, 15000);
  });

  ws.addEventListener('error', () => ws.close());
}

// ---------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------
nodes.power.addEventListener('click', toggle);
nodes.refresh.addEventListener('click', refresh);

window.addEventListener('resize', drawChart);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') loadState();
});

setInterval(updateFootnote, 1000);
setInterval(() => {
  if (document.visibilityState === 'visible') loadState();
}, 10000);

loadInfo();
loadState();
connectSocket();

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      /* sem service worker em HTTP simples: o app continua funcionando */
    });
  });
}
