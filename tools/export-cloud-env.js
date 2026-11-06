'use strict';

// Gera o valor de SMARTPLUG_CONFIG_JSON para hospedar o servidor fora de casa
// (modo nuvem). Imprime base64 do config atual com mode=cloud e um token novo.
//   node tools/export-cloud-env.js
// ATENCAO: a saida contem segredos. Cole direto no painel da hospedagem.

const crypto = require('node:crypto');
const configStore = require('../src/config');

const config = configStore.load();
if (!config.cloud?.enabled || !config.cloud.clientId || !config.cloud.clientSecret) {
  console.error('config.json sem credenciais da nuvem Tuya (rode npm run setup).');
  process.exit(1);
}

const token = config.server.token || crypto.randomBytes(18).toString('base64url');
const out = {
  mode: 'cloud',
  device: { id: config.device.id, name: config.device.name },
  cloud: config.cloud,
  dps: config.dps,
  scaling: config.scaling,
  codes: config.codes,
  server: { token },
};

console.error('\nVariaveis para a hospedagem:\n');
console.log(`SMARTPLUG_CONFIG_JSON=${Buffer.from(JSON.stringify(out)).toString('base64')}`);
console.error(`\nToken de acesso do app (digite no celular): ${token}\n`);
