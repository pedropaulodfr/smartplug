'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const CONFIG_PATH = process.env.SMARTPLUG_CONFIG || path.join(ROOT, 'config.json');

const DEFAULTS = {
  // 'auto'  = LAN primeiro, nuvem como reserva
  // 'local' = somente LAN
  // 'cloud' = somente nuvem (use ao hospedar o servidor fora de casa)
  mode: 'auto',
  device: {
    id: '',
    key: '',
    ip: '',
    version: 'auto', // 'auto' descobre na primeira conexao (3.1 - 3.5)
    name: 'Smart Plug',
  },
  // Credenciais do projeto Tuya, usadas apenas pelo modo nuvem.
  cloud: {
    enabled: false,
    clientId: '',
    clientSecret: '',
    region: 'us',
  },
  // Data points (DPs) do aparelho. Estes sao os IDs mais comuns da linha
  // Tuya 16A com medicao. Ajuste se o seu plug usar outro mapeamento.
  dps: {
    switch: 1,
    power: 19, // W
    voltage: 20, // 0.1 V (normalizado no device.js)
    current: 18, // mA (normalizado no device.js)
    energy: 17, // kWh acumulado
  },
  // Codigos dos DPs na nuvem (ex.: switch_1) — preenchidos pelo setup.
  codes: {},
  // Sobrescreve a auto-normalizacao das unidades. Ex.: { power: 0.1 }
  scaling: {
    power: null,
    voltage: null,
    current: null,
    energy: null,
  },
  server: {
    port: 3000,
    host: '0.0.0.0',
    token: '', // se preenchido, exige header x-token / ?token=
  },
  polling: {
    intervalMs: 5000,
    historyPoints: 120,
  },
};

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function merge(base, patch) {
  const out = { ...base };
  for (const [key, value] of Object.entries(patch || {})) {
    out[key] = isPlainObject(value) && isPlainObject(base[key])
      ? merge(base[key], value)
      : value;
  }
  return out;
}

function readFileConfig() {
  // Hospedagem sem disco/config.json: SMARTPLUG_CONFIG_JSON (JSON ou base64).
  const inline = process.env.SMARTPLUG_CONFIG_JSON;
  if (inline) {
    try {
      const text = inline.trim().startsWith('{')
        ? inline
        : Buffer.from(inline, 'base64').toString('utf8');
      return JSON.parse(text);
    } catch (error) {
      throw new Error(`SMARTPLUG_CONFIG_JSON invalido: ${error.message}`);
    }
  }
  try {
    return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return {};
    throw new Error(`config.json invalido (${CONFIG_PATH}): ${error.message}`);
  }
}

function fromEnv(config) {
  const env = process.env;
  const patch = {};
  if (env.SMARTPLUG_MODE) patch.mode = env.SMARTPLUG_MODE;
  if (env.TUYA_ID) patch.device = { ...(patch.device || {}), id: env.TUYA_ID };
  if (env.TUYA_KEY) patch.device = { ...(patch.device || {}), key: env.TUYA_KEY };
  if (env.TUYA_IP) patch.device = { ...(patch.device || {}), ip: env.TUYA_IP };
  if (env.TUYA_VERSION) patch.device = { ...(patch.device || {}), version: env.TUYA_VERSION };
  if (env.TUYA_NAME) patch.device = { ...(patch.device || {}), name: env.TUYA_NAME };
  if (env.PORT) patch.server = { ...(patch.server || {}), port: Number(env.PORT) };
  if (env.SMARTPLUG_TOKEN) patch.server = { ...(patch.server || {}), token: env.SMARTPLUG_TOKEN };
  return merge(config, patch);
}

function load() {
  const config = merge(DEFAULTS, readFileConfig());
  return fromEnv(config);
}

function save(patch) {
  const next = merge(load(), patch);
  fs.mkdirSync(path.dirname(CONFIG_PATH), { recursive: true });
  fs.writeFileSync(CONFIG_PATH, `${JSON.stringify(next, null, 2)}\n`, { mode: 0o600 });
  return next;
}

function isConfigured(config = load()) {
  if (config.mode === 'cloud') {
    return Boolean(config.device.id && config.cloud.enabled
      && config.cloud.clientId && config.cloud.clientSecret);
  }
  return Boolean(config.device.id && config.device.key);
}

module.exports = { DEFAULTS, CONFIG_PATH, load, save, isConfigured, merge };
