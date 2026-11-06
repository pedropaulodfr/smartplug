'use strict';

const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const express = require('express');
const { WebSocketServer } = require('ws');

const configStore = require('./config');
const { SmartPlug } = require('./device');

const flags = new Set(process.argv.slice(2));
const MOCK = flags.has('--mock');
const PUBLIC_DIR = path.join(__dirname, '..', 'public');

const config = configStore.load();
const log = {
  info: (...args) => console.log(...args),
  warn: (...args) => console.warn(...args),
  debug: () => {},
};

if (!MOCK && !configStore.isConfigured(config)) {
  console.error('\n  Aparelho ainda nao configurado.');
  console.error('  1) npm run scan   -> descubra o Device ID');
  console.error('  2) npm run setup  -> informe o Device ID e as credenciais da Tuya');
  console.error('  3) npm start\n');
  console.error('  Para ver a interface sem o plug, rode: npm run mock\n');
  process.exit(1);
}

const plug = new SmartPlug(config, { mock: MOCK, log });

const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '32kb' }));

const token = config.server.token;
function requireToken(req, res, next) {
  if (!token) return next();
  const provided = req.get('x-token') || req.query.token;
  if (provided !== token) {
    return res.status(401).json({ error: 'token invalido' });
  }
  return next();
}

app.get('/api/state', requireToken, (_req, res) => {
  res.json(plug.snapshot());
});

app.get('/api/health', (_req, res) => {
  res.json({ ok: true, mock: MOCK, uptime: Math.round(process.uptime()) });
});

app.get('/api/info', requireToken, (_req, res) => {
  const { device, dps } = config;
  res.json({
    name: device.name,
    id: device.id,
    ip: device.ip || plug.state.ip,
    version: plug.state.version || device.version,
    dps,
    mock: MOCK,
  });
});

app.post('/api/power', requireToken, async (req, res) => {
  const { on } = req.body ?? {};
  if (typeof on !== 'boolean') {
    return res.status(400).json({ error: 'envie { "on": true | false }' });
  }

  try {
    const state = await plug.setPower(on);
    return res.json({ ok: true, state });
  } catch (error) {
    return res.status(503).json({ error: error.message, state: plug.snapshot() });
  }
});

app.use(express.static(PUBLIC_DIR, {
  extensions: ['html'],
  setHeaders(res, filePath) {
    if (filePath.endsWith('sw.js') || filePath.endsWith('.html')) {
      res.setHeader('Cache-Control', 'no-cache');
    }
  },
}));

app.use((_req, res) => {
  res.status(404).json({ error: 'nao encontrado' });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

function broadcast(message) {
  const data = JSON.stringify(message);
  for (const client of wss.clients) {
    if (client.readyState === 1) client.send(data);
  }
}

wss.on('connection', (socket, req) => {
  if (token) {
    const url = new URL(req.url, 'http://localhost');
    if (url.searchParams.get('token') !== token) {
      socket.close(1008, 'token invalido');
      return;
    }
  }

  socket.isAlive = true;
  socket.on('pong', () => {
    socket.isAlive = true;
  });
  socket.send(JSON.stringify({ type: 'state', state: plug.snapshot() }));

  plug.setViewers(wss.clients.size);
  socket.on('close', () => {
    plug.setViewers(Math.max(0, wss.clients.size - 1));
  });
});

setInterval(() => {
  for (const socket of wss.clients) {
    if (socket.isAlive === false) {
      socket.terminate();
      continue;
    }
    socket.isAlive = false;
    socket.ping();
  }
}, 30000).unref();

plug.on('state', (state) => broadcast({ type: 'state', state }));

function localAddresses() {
  const addresses = [];
  for (const interfaces of Object.values(os.networkInterfaces())) {
    for (const iface of interfaces || []) {
      if (iface.family === 'IPv4' && !iface.internal) addresses.push(iface.address);
    }
  }
  return addresses;
}

server.listen(config.server.port, config.server.host, () => {
  const port = config.server.port;
  log.info('');
  log.info('  Smart Plug - controle local');
  log.info(`  Aparelho : ${MOCK ? 'SIMULADO' : config.device.name}`);
  log.info(`  Interface: http://localhost:${port}`);
  for (const address of localAddresses()) {
    log.info(`             http://${address}:${port}   (celular)`);
  }
  if (token) log.info('  Token    : ativo (x-token)');
  log.info('');
});

plug.start();

function shutdown() {
  log.info('\n  Encerrando...');
  plug.stop();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('unhandledRejection', (error) => {
  log.warn('[server] promise rejeitada:', error?.message || error);
});
