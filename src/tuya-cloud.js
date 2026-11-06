'use strict';

const crypto = require('node:crypto');

/**
 * Cliente minimo da API de nuvem da Tuya.
 *
 * Usado apenas uma vez, no `npm run setup`, para descobrir a `local_key`
 * do aparelho. Depois disso o controle acontece 100% na rede local.
 *
 * Detalhe importante da assinatura: em chamadas autenticadas o
 * `access_token` entra no material do HMAC, depois do `client_id`
 * (a documentacao publica omite isso).
 */

const REGIONS = {
  us: 'https://openapi.tuyaus.com', // Western America
  'us-east': 'https://openapi-ueaz.tuyaus.com', // Eastern America
  eu: 'https://openapi.tuyaeu.com', // Central Europe
  'eu-west': 'https://openapi-weaz.tuyaeu.com', // Western Europe
  cn: 'https://openapi.tuyacn.com', // China
  in: 'https://openapi.tuyain.com', // India
  sg: 'https://openapi-sg.iotbing.com', // Singapore
};

// "data center suspenso" e "acesso cross-region bloqueado": projeto nao mora aqui.
const WRONG_DATACENTER = new Set([28841107, 2007]);

class TuyaCloudError extends Error {
  constructor(message, { code, payload } = {}) {
    super(message);
    this.name = 'TuyaCloudError';
    this.code = code;
    this.payload = payload;
  }
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function sign({ clientId, secret, t, nonce, method, pathWithQuery, body = '', token = '' }) {
  const stringToSign = `${method}\n${sha256(body)}\n\n${pathWithQuery}`;
  const material = clientId + (token || '') + t + nonce + stringToSign;
  return crypto.createHmac('sha256', secret).update(material).digest('hex').toUpperCase();
}

async function call({ clientId, clientSecret, region = 'us', method = 'GET', path: route, token, body }) {
  const base = REGIONS[region];
  if (!base) {
    throw new TuyaCloudError(`Regiao invalida: ${region}. Use uma de: ${Object.keys(REGIONS).join(', ')}`);
  }

  const payload = body === undefined ? '' : JSON.stringify(body);
  const t = String(Date.now());
  const nonce = crypto.randomUUID();
  const headers = {
    client_id: clientId,
    sign: sign({ clientId, secret: clientSecret, t, nonce, method, pathWithQuery: route, body: payload, token }),
    t,
    sign_method: 'HMAC-SHA256',
    nonce,
  };
  if (token) headers.access_token = token;
  if (payload) headers['content-type'] = 'application/json';

  const response = await fetch(base + route, { method, headers, body: payload || undefined });
  const text = await response.text();

  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new TuyaCloudError(`Resposta invalida da Tuya (HTTP ${response.status})`, { payload: text });
  }

  if (!json.success) {
    const hint = json.code === 1004
      ? ' (credenciais invalidas: confira API ID/Secret)'
      : json.code === 1106
        ? ' (o aparelho nao esta vinculado a este projeto)'
        : json.code === 28841107
          ? ' (data center errado para este projeto)'
          : '';
    throw new TuyaCloudError(`${json.msg || 'erro desconhecido'} [code ${json.code}]${hint}`, {
      code: json.code,
      payload: json,
    });
  }

  return json.result;
}

async function getToken({ clientId, clientSecret, region }) {
  const result = await call({
    clientId,
    clientSecret,
    region,
    method: 'GET',
    path: '/v1.0/token?grant_type=1',
  });
  return result.access_token;
}

async function getDevice({ clientId, clientSecret, region, deviceId }) {
  const token = await getToken({ clientId, clientSecret, region });
  return call({
    clientId,
    clientSecret,
    region,
    path: `/v1.0/devices/${encodeURIComponent(deviceId)}`,
    token,
  });
}

/**
 * Testa cada data center e classifica a resposta:
 *   ok         -> aparelho encontrado
 *   denied     -> credenciais e data center corretos, aparelho nao vinculado
 *   suspended  -> data center errado para este projeto
 *   token-fail -> credenciais invalidas naquele endpoint
 */
async function probeRegions({ clientId, clientSecret, deviceId }) {
  const results = [];

  for (const region of Object.keys(REGIONS)) {
    let token;
    try {
      token = await getToken({ clientId, clientSecret, region });
    } catch (error) {
      results.push({ region, status: 'token-fail', code: error.code, message: error.message });
      continue;
    }

    if (!deviceId) {
      results.push({ region, status: 'token-ok' });
      continue;
    }

    try {
      await call({ clientId, clientSecret, region, path: `/v1.0/devices/${encodeURIComponent(deviceId)}`, token });
      results.push({ region, status: 'ok' });
    } catch (error) {
      results.push({
        region,
        status: WRONG_DATACENTER.has(error.code) ? 'suspended' : 'denied',
        code: error.code,
        message: error.message,
      });
    }
  }

  return results;
}

async function detectRegion({ clientId, clientSecret, deviceId }) {
  const results = await probeRegions({ clientId, clientSecret, deviceId });

  const usable = results.find((item) => item.status === 'ok')
    || results.find((item) => item.status === 'denied')
    || results.find((item) => item.status === 'token-ok');

  if (usable) return usable.region;

  const detail = results
    .map((item) => `  ${item.region}: ${item.code ?? '?'} ${item.message ?? item.status}`)
    .join('\n');
  throw new TuyaCloudError(`Nao consegui autenticar em nenhum data center.\n${detail}`);
}

/**
 * Especificacao do produto: traz o mapeamento oficial de `code` -> `dp_id`,
 * que varia por modelo (nem todo plug usa 1/18/19/20/17).
 */
async function getSpecifications({ clientId, clientSecret, region, deviceId }) {
  const token = await getToken({ clientId, clientSecret, region });
  return call({
    clientId,
    clientSecret,
    region,
    path: `/v1.1/devices/${encodeURIComponent(deviceId)}/specifications`,
    token,
  });
}

module.exports = {
  REGIONS,
  TuyaCloudError,
  call,
  getToken,
  getDevice,
  getSpecifications,
  probeRegions,
  detectRegion,
  sign,
};
