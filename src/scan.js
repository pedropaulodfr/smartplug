'use strict';

/**
 * Descobre os dispositivos Tuya na rede local (broadcast UDP 6666/6667).
 * Nao precisa de chave nem de nuvem: serve para pegar o Device ID e o IP.
 *
 *   npm run scan
 *   node src/scan.js 15   # escuta por 15 segundos
 */

const TuyAPI = require('tuyapi');

async function main() {
  const seconds = Number(process.argv[2]) > 0 ? Number(process.argv[2]) : 8;

  console.log(`\n  Procurando dispositivos Tuya na rede por ${seconds}s...`);
  console.log('  (o aparelho precisa estar ligado e na mesma rede deste computador)\n');

  const probe = new TuyAPI({
    id: '00000000000000000000',
    key: '0000000000000000',
    version: '3.3',
  });

  let devices = [];
  try {
    devices = await probe.find({ all: true, timeout: seconds });
  } catch (error) {
    console.error(`  Falha ao escanear: ${error.message}`);
    if (/EADDRINUSE/.test(error.message)) {
      console.error('  A porta UDP 6666/6667 ja esta em uso por outro programa (o servidor deste projeto?).');
    }
    process.exitCode = 1;
    return;
  }

  if (!Array.isArray(devices) || devices.length === 0) {
    console.log('  Nenhum dispositivo encontrado.');
    console.log('  Dicas: confira se o plug esta energizado, na mesma rede/Wi-Fi e');
    console.log('  se o roteador nao bloqueia broadcast/UDP entre clientes (AP isolation).\n');
    return;
  }

  console.log(`  ${devices.length} dispositivo(s) encontrado(s):\n`);
  for (const device of devices) {
    console.log(`    Device ID : ${device.id}`);
    console.log(`    IP        : ${device.ip}`);
    console.log('');
  }

  console.log('  Use o Device ID acima no proximo passo:  npm run setup\n');
}

main();
