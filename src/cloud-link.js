'use strict';

const { call } = require('./tuya-cloud');
const { normalize } = require('./normalize');

const DEFAULT_CODES = {
  switch: 'switch_1',
  power: 'cur_power',
  voltage: 'cur_voltage',
  current: 'cur_current',
  energy: 'add_ele',
};

/**
 * Controle pela nuvem da Tuya (funciona de qualquer lugar).
 *
 * Recebe e envia os mesmos campos que o controle local; a diferenca e que
 * aqui falamos com openapi.tuyaus.com em vez da porta 6668 do aparelho.
 */
class CloudLink {
  constructor(config) {
    this.clientId = config.cloud?.clientId || '';
    this.clientSecret = config.cloud?.clientSecret || '';
    this.region = config.cloud?.region || 'us';
    this.deviceId = config.device?.id || '';
    this.config = config;
    this.codes = { ...DEFAULT_CODES, ...(config.codes || {}) };
    this.token = null;
    this.expiresAt = 0;
  }

  get available() {
    return Boolean(this.clientId && this.clientSecret && this.deviceId);
  }

  async _token(force = false) {
    if (!force && this.token && Date.now() < this.expiresAt) return this.token;

    const result = await call({
      clientId: this.clientId,
      clientSecret: this.clientSecret,
      region: this.region,
      path: '/v1.0/token?grant_type=1',
    });

    this.token = result.access_token;
    const ttl = Number(result.expire_time) || 3600;
    this.expiresAt = Date.now() + Math.max(60, ttl - 120) * 1000;
    return this.token;
  }

  async _request({ method = 'GET', path, body }) {
    const send = async (token) => call({
      clientId: this.clientId,
      clientSecret: this.clientSecret,
      region: this.region,
      method,
      path,
      body,
      token,
    });

    const token = await this._token();
    try {
      return await send(token);
    } catch (error) {
      const retriable = error.code === 1010 || error.code === 1011 || /token/i.test(error.message || '');
      if (!retriable) throw error;
      return send(await this._token(true));
    }
  }

  async status() {
    const items = await this._request({ path: `/v1.0/devices/${this.deviceId}/status` });
    const byCode = {};
    for (const item of items || []) byCode[item.code] = item.value;

    const raw = {};
    for (const field of Object.keys(DEFAULT_CODES)) {
      const code = this.codes[field];
      const dp = this.config.dps?.[field];
      if (dp !== undefined && byCode[code] !== undefined) raw[dp] = byCode[code];
    }

    return { ...normalize(raw, this.config), raw };
  }

  async setPower(on) {
    await this._request({
      method: 'POST',
      path: `/v1.0/devices/${this.deviceId}/commands`,
      body: { commands: [{ code: this.codes.switch, value: Boolean(on) }] },
    });
    return true;
  }
}

module.exports = { CloudLink, DEFAULT_CODES };
