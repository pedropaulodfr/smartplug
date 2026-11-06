'use strict';

const { EventEmitter } = require('node:events');
const TuyAPI = require('tuyapi');
const configStore = require('./config');
const { normalize } = require('./normalize');
const { CloudLink } = require('./cloud-link');

const CANDIDATE_VERSIONS = ['3.3', '3.4', '3.5', '3.2', '3.1'];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function withTimeout(promise, ms, label = 'timeout') {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(label)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * Mantem uma conexao local (TCP 6668) com o plug e publica um estado
 * normalizado: ligado/desligado, potencia, tensao, corrente e energia.
 */
class SmartPlug extends EventEmitter {
  constructor(config, { mock = false, log = console } = {}) {
    super();
    this.config = config;
    this.mock = mock;
    this.log = log;

    this.client = null;
    this.stopped = false;
    this.connecting = false;
    this.pollTimer = null;
    this.polling = false;
    this.reconnectTimer = null;
    this.backoffMs = 1000;
    this.emitTimer = null;
    this.pendingEmit = false;
    this.lastHistoryAt = 0;

    this.cloud = null;
    this.cloudTimer = null;
    this.cloudBusy = false;
    this.viewers = 0;
    this.knownVersion = config.device.version && config.device.version !== 'auto'
      ? config.device.version
      : null;

    if (config.mode !== 'local' && config.cloud?.enabled) {
      const cloud = new CloudLink(config);
      if (cloud.available) this.cloud = cloud;
    }

    this.state = {
      online: false,
      on: null,
      power: 0,
      voltage: 0,
      current: 0,
      energy: 0,
      updatedAt: null,
      latency: null,
      name: config.device.name,
      ip: config.device.ip || null,
      version: config.device.version === 'auto' ? null : config.device.version,
      lastError: null,
      raw: null,
      via: null,
      mock,
    };

    this.history = [];
  }

  snapshot() {
    return {
      ...this.state,
      history: this.history.slice(-this.config.polling.historyPoints),
    };
  }

  async start() {
    if (this.mock) {
      this._startMock();
      return;
    }

    if (this.config.mode === 'cloud' && this.cloud) {
      this.log.info?.('[plug] modo nuvem (sem acesso a LAN)');
      this._startCloudPolling();
      return;
    }

    this._connect();
  }

  setViewers(count) {
    this.viewers = count;
    // Ajusta a frequencia das consultas a nuvem conforme alguem esta olhando.
    if (this.cloudTimer) {
      this._stopCloudPolling();
      this._startCloudPolling();
    }
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.reconnectTimer);
    clearInterval(this.cloudTimer);
    this._teardown();
  }

  // ---------------------------------------------------------------------
  // Ligar / desligar
  // ---------------------------------------------------------------------
  async setPower(on) {
    const desired = Boolean(on);

    if (this.mock) {
      this._mockSetPower(desired);
      return this.snapshot();
    }

    if (!this.client || !this.client.isConnected()) {
      if (this.cloud) return this._setPowerCloud(desired);
      await this._connect();
      if (!this.client || !this.client.isConnected()) {
        throw new Error('Plug offline: nem LAN nem nuvem disponiveis.');
      }
    }

    const previous = this.state.on;
    this._applyState({ on: desired, updatedAt: Date.now() }, { force: true });

    try {
      await this.client.set({
        dps: this.config.dps.switch,
        set: desired,
        shouldWaitForResponse: false,
      });
    } catch (error) {
      this._applyState({ on: previous, lastError: error.message }, { force: true });
      throw new Error(`Falha ao enviar comando: ${error.message}`);
    }

    // Confirma o estado real no aparelho (o comando nao aguarda resposta).
    await sleep(350);
    await this._poll();
    return this.snapshot();
  }

  // ---------------------------------------------------------------------
  // Conexao
  // ---------------------------------------------------------------------
  async _connect() {
    if (this.stopped || this.connecting) return;
    this.connecting = true;

    try {
      this._teardown();

      const { id, key } = this.config.device;
      if (!id || !key) throw new Error('Aparelho nao configurado (rode `npm run setup`).');

      const ip = await this._resolveIp();
      const version = await this._resolveVersion(ip, id, key);

      const client = new TuyAPI({
        id,
        key,
        ip,
        version,
        issueGetOnConnect: false,
        issueRefreshOnConnect: true,
        issueRefreshOnPing: true,
        nullPayloadOnJSONError: true,
      });

      client.on('data', (payload) => this._handlePayload(payload));
      client.on('dp-refresh', (payload) => this._handlePayload(payload));
      client.on('disconnected', () => this._handleDown('conexao encerrada'));
      client.on('error', (error) => {
        const message = typeof error === 'string' ? error : error?.message || 'erro';
        this.state.lastError = message;
        this.log.debug?.(`[plug] ${message}`);
      });

      this.client = client;
      this.state.ip = ip;
      this.state.version = version;

      await withTimeout(client.connect(), 8000, 'timeout ao conectar');
      this.backoffMs = 1000;
      this._stopCloudPolling();

      this._applyState({ online: true, lastError: null, via: 'local' }, { force: true });
      this.log.info?.(`[plug] conectado em ${ip} (protocolo ${version})`);

      await this._poll();
      this._startPolling();
    } catch (error) {
      this._applyState(
        { online: this.state.via === 'cloud', lastError: error.message },
        { force: true },
      );
      this._scheduleReconnect(error.message);
      if (this.cloud) this._startCloudPolling();
    } finally {
      this.connecting = false;
    }
  }

  async _resolveIp() {
    if (this.config.device.ip) return this.config.device.ip;

    const probe = new TuyAPI({
      id: this.config.device.id,
      key: this.config.device.key,
      version: this.config.device.version === 'auto' ? '3.3' : this.config.device.version,
      issueGetOnConnect: false,
    });
    probe.on('error', () => {});

    await probe.find({ timeout: 12 });
    const ip = probe.device.ip;
    if (!ip) throw new Error('aparelho nao encontrado na rede local');

    this.log.info?.(`[plug] encontrado em ${ip}`);
    this.config.device.ip = ip;
    return ip;
  }

  async _resolveVersion(ip, id, key) {
    if (this.config.device.version !== 'auto') return this.config.device.version;

    const candidates = this.knownVersion
      ? [this.knownVersion, ...CANDIDATE_VERSIONS.filter((item) => item !== this.knownVersion)]
      : CANDIDATE_VERSIONS;

    for (const version of candidates) {
      const probe = new TuyAPI({
        id,
        key,
        ip,
        version,
        issueGetOnConnect: false,
        issueRefreshOnConnect: false,
      });
      probe.on('error', () => {});

      const closeProbe = () => {
        try {
          probe.disconnect();
        } catch {
          /* ignora */
        }
      };

      try {
        await withTimeout(probe.connect(), 6000, 'timeout ao conectar');
      } catch (error) {
        closeProbe();
        // Porta fechada/inacessivel: nao adianta testar outras versoes.
        throw new Error(`sem conexao TCP com o aparelho (${error.message})`);
      }

      try {
        const data = await withTimeout(probe.get({ schema: true }), 4000, 'aparelho nao respondeu');
        closeProbe();
        if (data && typeof data === 'object' && data.dps) {
          this.log.info?.(`[plug] protocolo ${version} confirmado`);
          this.knownVersion = version;
          try {
            configStore.save({ device: { version } });
            this.config.device.version = version;
          } catch {
            /* nao critico */
          }
          return version;
        }
      } catch (error) {
        closeProbe();
        // Porta abriu mas o aparelho ficou mudo: esta ocupado (app no celular),
        // nao e versao errada. Falha rapido para o fallback de nuvem entrar.
        if (/timeout|respondeu/i.test(error.message)) {
          throw new Error('aparelho nao respondeu (outro app conectado?)');
        }
      }

      await sleep(150);
    }

    this.log.warn?.('[plug] nao consegui confirmar o protocolo; usando 3.3');
    return '3.3';
  }

  _handleDown(reason) {
    if (this.stopped) return;
    clearInterval(this.pollTimer);
    this.pollTimer = null;
    // Se a nuvem esta entregando dados, o app continua online.
    this._applyState({ online: this.state.via === 'cloud' }, { force: true });
    this._scheduleReconnect(reason);
    if (this.cloud) this._startCloudPolling();
  }

  _scheduleReconnect(reason) {
    if (this.stopped || this.reconnectTimer) return;

    const delay = this.backoffMs;
    this.backoffMs = Math.min(this.backoffMs * 2, 30000);
    this.log.warn?.(`[plug] reconectando em ${Math.round(delay / 1000)}s (${reason})`);

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this._connect();
    }, delay);
  }

  _teardown() {
    clearInterval(this.pollTimer);
    this.pollTimer = null;

    const client = this.client;
    this.client = null;
    if (client) {
      try {
        client.removeAllListeners();
        client.disconnect();
      } catch {
        /* ignora */
      }
    }
  }

  _startPolling() {
    clearInterval(this.pollTimer);
    this.pollTimer = setInterval(() => this._poll(), this.config.polling.intervalMs);
    this.pollTimer.unref?.();
  }

  // ---------------------------------------------------------------------
  // Leitura de estado
  // ---------------------------------------------------------------------
  async _poll() {
    const client = this.client;
    if (!client || !client.isConnected() || this.polling) return;

    this.polling = true;
    const startedAt = Date.now();
    try {
      const data = await withTimeout(client.get({ schema: true }), 6000, 'timeout ao ler status');
      this.state.latency = Date.now() - startedAt;
      this._handlePayload(data);
    } catch (error) {
      this.state.lastError = error.message;
      if (!client.isConnected()) this._handleDown('sem resposta');
    } finally {
      this.polling = false;
    }
  }

  _handlePayload(payload) {
    if (!payload || typeof payload !== 'object') return;
    const dps = payload.dps;
    if (!dps || typeof dps !== 'object') return;

    this._applyPayload(normalize(dps, this.config), dps, 'local');
  }

  _applyPayload(values, raw, via) {
    this._applyState({
      online: true,
      via,
      on: values.on ?? this.state.on,
      power: round(values.power ?? 0, 1),
      voltage: round(values.voltage ?? 0, 1),
      current: round(values.current ?? 0, 3),
      energy: round(values.energy ?? this.state.energy, 3),
      updatedAt: Date.now(),
      raw,
    });

    this._recordHistory();
  }

  // ---------------------------------------------------------------------
  // Nuvem (modo remoto e fallback quando a LAN falha)
  // ---------------------------------------------------------------------
  _startCloudPolling() {
    if (this.stopped || !this.cloud || this.cloudTimer) return;

    const interval = this.viewers > 0 ? 15000 : 90000;
    this.log.info?.(`[plug] usando a nuvem (consulta a cada ${interval / 1000}s)`);

    this.cloudTimer = setInterval(() => this._pollCloud(), interval);
    this.cloudTimer.unref?.();
    this._pollCloud();
  }

  _stopCloudPolling() {
    if (!this.cloudTimer) return;
    clearInterval(this.cloudTimer);
    this.cloudTimer = null;
  }

  async _pollCloud() {
    if (!this.cloud || this.cloudBusy) return;

    this.cloudBusy = true;
    const startedAt = Date.now();
    try {
      const values = await this.cloud.status();
      this.state.latency = Date.now() - startedAt;
      this._applyPayload(values, values.raw, 'cloud');
    } catch (error) {
      this.state.lastError = error.message;
      this.log.warn?.(`[plug] nuvem: ${error.message}`);
    } finally {
      this.cloudBusy = false;
    }
  }

  async _setPowerCloud(desired) {
    const previous = this.state.on;
    this._applyState({ on: desired, updatedAt: Date.now(), via: 'cloud' }, { force: true });

    try {
      await this.cloud.setPower(desired);
    } catch (error) {
      this._applyState({ on: previous, lastError: error.message }, { force: true });
      throw new Error(`Falha ao enviar comando pela nuvem: ${error.message}`);
    }

    await sleep(700);
    await this._pollCloud();
    return this.snapshot();
  }

  _recordHistory() {
    const now = Date.now();
    if (now - this.lastHistoryAt < 1500) return;
    this.lastHistoryAt = now;
    this.history.push({ t: now, p: this.state.power });
    if (this.history.length > this.config.polling.historyPoints * 2) {
      this.history.splice(0, this.history.length - this.config.polling.historyPoints);
    }
  }

  _applyState(patch, { force = false } = {}) {
    const changed = Object.entries(patch).some(([key, value]) => this.state[key] !== value);
    Object.assign(this.state, patch);
    if (!changed && !force) return;
    this._scheduleEmit();
  }

  _scheduleEmit() {
    if (this.emitTimer) {
      this.pendingEmit = true;
      return;
    }
    this.emitTimer = setTimeout(() => {
      this.emitTimer = null;
      this.emit('state', this.snapshot());
      if (this.pendingEmit) {
        this.pendingEmit = false;
        this._scheduleEmit();
      }
    }, 150);
  }

  // ---------------------------------------------------------------------
  // Modo simulado (npm run mock) — permite validar a interface sem plug
  // ---------------------------------------------------------------------
  _startMock() {
    this.log.info?.('[plug] MODO SIMULADO ativo');
    this.state.online = true;
    this.state.on = false;
    this.state.updatedAt = Date.now();
    this._applyState({}, { force: true });

    this.mockTimer = setInterval(() => {
      if (!this.state.on) {
        this._applyState({ power: 0, current: 0, voltage: 220, updatedAt: Date.now() });
        this._recordHistory();
        return;
      }
      const base = 900 + Math.sin(Date.now() / 40000) * 350;
      const power = Math.max(0, base + (Math.random() - 0.5) * 120);
      const voltage = 219 + Math.random() * 4;
      this._applyState({
        power: round(power, 1),
        voltage: round(voltage, 1),
        current: round(power / voltage, 3),
        energy: round(this.state.energy + (power * 2) / 3600000, 6),
        updatedAt: Date.now(),
        latency: 12 + Math.round(Math.random() * 20),
      });
      this._recordHistory();
    }, 2000);
    this.mockTimer.unref?.();
  }

  _mockSetPower(on) {
    this._applyState({ on, updatedAt: Date.now(), lastError: null }, { force: true });
    if (!on) this._applyState({ power: 0, current: 0, voltage: 220 });
    else this._applyState({ voltage: 220, power: 950, current: 4.318 });
  }
}

function round(value, digits) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

module.exports = { SmartPlug };
