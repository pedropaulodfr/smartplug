'use strict';

/**
 * Configuracao assistida.
 *
 * A `local_key` do aparelho so pode ser obtida na nuvem da Tuya. Este script
 * faz isso UMA vez e grava tudo no config.json. Depois disso o controle e
 * 100% local.
 *
 * Pre-requisito: conta em https://iot.tuya.com com um Cloud Project e a sua
 * conta do Smart Life vinculada (Devices -> Link App Account). Veja o README.
 *
 *   npm run setup
 *   node src/setup.js --id <device-id> --ip 192.168.0.50 --region auto
 *   node src/setup.js --probe --client-id <id> --client-secret <secret>
 */

const readline = require('node:readline/promises');
const { stdin, stdout } = require('node:process');
const TuyAPI = require('tuyapi');
const configStore = require('./config');
const { getDevice, getSpecifications, probeRegions, detectRegion, REGIONS } = require('./tuya-cloud');
const { mapFromSpecifications } = require('./dps');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith('--')) continue;
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) {
      args[key] = next;
      i += 1;
    } else {
      args[key] = true;
    }
  }
  return args;
}

async function resolveRegion({ requested, clientId, clientSecret, deviceId }) {
  if (requested && requested !== 'auto') {
    if (!REGIONS[requested]) {
      throw new Error(`Regiao invalida: ${requested}. Opcoes: auto, ${Object.keys(REGIONS).join(', ')}`);
    }
    return requested;
  }

  stdout.write('  Detectando o data center do projeto... ');
  const region = await detectRegion({ clientId, clientSecret, deviceId });
  console.log(`ok (${region})`);
  return region;
}

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(label)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

async function testConnection({ id, key, ip }) {
  let lastError = 'nao conectou';

  for (const version of ['3.3', '3.4', '3.5']) {
    const client = new TuyAPI({
      id,
      key,
      ip: ip || undefined,
      version,
      issueGetOnConnect: false,
    });

    // O tuyapi emite 'error' em eventos do socket; sem listener, o Node derruba o processo.
    client.on('error', () => {});

    try {
      if (!client.device.ip) {
        await withTimeout(client.find({ timeout: 8 }), 12000, 'aparelho nao encontrado na rede');
      }
      await withTimeout(client.connect(), 8000, 'timeout ao conectar');
      const data = await withTimeout(client.get({ schema: true }), 6000, 'aparelho nao respondeu');
      return { version, dps: data?.dps || null };
    } catch (error) {
      lastError = `${error.message} (protocolo ${version})`;
    } finally {
      try {
        client.disconnect();
      } catch {
        /* ignora */
      }
    }
  }

  return { error: lastError };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const rl = readline.createInterface({ input: stdin, output: stdout });
  const ask = async (label, fallback = '') => {
    const answer = (await rl.question(label)).trim();
    return answer || fallback;
  };

  try {
    // Modo diagnostico: mostra o que cada data center responde.
    if (args.probe) {
      const clientId = args['client-id'] || (await ask('  API ID (Client ID): '));
      const clientSecret = args['client-secret'] || (await ask('  API Secret (Client Secret): '));
      const deviceId = args.id || '';

      console.log('\n  Testando data centers...\n');
      const results = await probeRegions({ clientId, clientSecret, deviceId });
      const labels = {
        ok: 'aparelho encontrado',
        denied: 'data center certo, aparelho nao vinculado',
        suspended: 'data center errado',
        'token-ok': 'credenciais ok',
        'token-fail': 'credenciais invalidas',
      };
      for (const item of results) {
        console.log(`    ${item.region.padEnd(8)} ${labels[item.status] || item.status}`);
      }

      const best = results.find((item) => item.status === 'ok')
        || results.find((item) => item.status === 'denied')
        || results.find((item) => item.status === 'token-ok');
      console.log(best ? `\n  Data center do projeto: ${best.region}\n` : '\n  Nenhum data center utilizavel.\n');
      return;
    }

    console.log('\n  === Configuracao do Smart Plug ===\n');
    console.log('  Precisamos do Device ID e das credenciais do projeto na nuvem');
    console.log('  Tuya (usadas apenas para ler a local_key do aparelho).\n');

    const id = args.id || (await ask('  Device ID (use `npm run scan` para descobrir): '));
    if (!id) throw new Error('Device ID obrigatorio.');

    const ip = args.ip || (await ask('  IP local do plug (Enter = descobrir sozinho): '));

    const clientId = args['client-id'] || (await ask('  API ID (Client ID) do projeto Tuya: '));
    if (!clientId) throw new Error('API ID obrigatorio.');

    const clientSecret = args['client-secret'] || (await ask('  API Secret (Client Secret): '));
    if (!clientSecret) throw new Error('API Secret obrigatorio.');

    const region = await resolveRegion({
      requested: args.region,
      clientId,
      clientSecret,
      deviceId: id,
    });

    console.log('\n  Consultando o aparelho na nuvem da Tuya...');

    let device;
    try {
      device = await getDevice({ clientId, clientSecret, region, deviceId: id });
    } catch (error) {
      const hint = [
        '',
        '  Dica: a aba Devices do projeto precisa listar o aparelho.',
        '  Se estiver vazia, va em Devices -> Link App Account -> Add App Account',
        '  e leia o QR code com o app Smart Life (aba Me -> icone de QR).',
        '  Confira tambem se os servicos IoT Core e Authorization estao autorizados.',
      ].join('\n');
      throw new Error(`${error.message}\n${hint}`);
    }

    const localKey = device.local_key;
    if (!localKey || localKey.length !== 16) {
      throw new Error(`local_key invalida recebida (${localKey ? localKey.length : 0} caracteres).`);
    }

    // Descobre o mapa de DPs do modelo (cada fabricante usa IDs proprios).
    let mapping = null;
    try {
      const spec = await getSpecifications({ clientId, clientSecret, region, deviceId: id });
      mapping = mapFromSpecifications(spec);
    } catch (error) {
      console.log(`  Aviso: nao consegui ler a especificacao do produto (${error.message}).`);
      console.log('  Usando o mapa padrao; ajuste em `dps` no config.json se precisar.');
    }

    const previous = configStore.load();
    const dps = mapping && Object.keys(mapping.dps).length
      ? { ...previous.dps, ...mapping.dps }
      : previous.dps;
    const scaling = mapping && Object.keys(mapping.scaling).length
      ? { ...previous.scaling, ...mapping.scaling }
      : previous.scaling;

    const config = configStore.save({
      device: {
        id,
        key: localKey,
        ip,
        name: device.name || previous.device.name,
        version: previous.device.version && previous.device.version !== 'auto'
          ? previous.device.version
          : 'auto',
      },
      dps,
      scaling,
      codes: mapping?.codes || previous.codes,
      cloud: args['no-cloud']
        ? previous.cloud
        : { enabled: true, clientId, clientSecret, region },
    });

    console.log('\n  Aparelho configurado:');
    console.log(`    Nome      : ${config.device.name}`);
    console.log(`    Device ID : ${config.device.id}`);
    console.log(`    IP        : ${config.device.ip || '(descoberto na conexao)'}`);
    console.log(`    Data center: ${region}`);

    const dpList = Object.entries(dps)
      .map(([field, dp]) => `${field}=${dp}`)
      .join('  ');
    console.log(`    DPs       : ${dpList}`);

    const scaleList = Object.entries(scaling)
      .filter(([, scale]) => typeof scale === 'number')
      .map(([field, scale]) => `${field} x${scale}`)
      .join('  ');
    if (scaleList) console.log(`    Escalas   : ${scaleList}`);
    if (config.cloud.enabled) {
      console.log('    Nuvem     : habilitada (fallback e modo remoto)');
      console.log('  O Secret ficou gravado em config.json para permitir o modo nuvem.');
      console.log('  Para nao guardar, rode o setup com --no-cloud.');
    }
    console.log('\n  Arquivo gravado em config.json (nao versione esse arquivo!).');

    stdout.write('\n  Testando a conexao local... ');
    const result = await testConnection({
      id,
      key: localKey,
      ip: config.device.ip,
    });

    if (result && result.error) {
      console.log(`nao confirmado (${result.error}).`);
      console.log('  O servidor tenta de novo, inclusive outros protocolos, ao iniciar.');
    } else {
      console.log(`ok! (protocolo ${result.version})`);
      console.log(`  Estado atual: ${JSON.stringify(result.dps)}`);
    }

    console.log('\n  Agora rode:  npm start\n');
  } finally {
    rl.close();
  }
}

main().catch((error) => {
  console.error(`\n  Erro: ${error.message}\n`);
  process.exitCode = 1;
});
