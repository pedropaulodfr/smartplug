# AGENTS.md

Guia de entrada para quem (humano ou IA) for continuar este projeto.
Leia nesta ordem: **este arquivo → `docs/ARQUITETURA.md` → `README.md`**.

## O que é

Servidor Node.js + PWA que controla um smart plug Tuya (WGHINE T34) por dois
caminhos: **LAN** (TCP 6668, preferencial) e **nuvem Tuya** (fallback e modo
remoto). Sem banco de dados, sem framework no front, sem build.

## Rodar

```bash
npm install
npm start          # http://localhost:3000
npm run mock       # UI sem hardware
npm run scan       # descobre Device ID/IP na LAN
npm run setup      # configura aparelho (pede credenciais Tuya)
```

Requisitos: Node 18+ (aqui: v24). `config.json` é obrigatório para `npm start`
(fora do mock) e **não é versionado**.

## Onde está o quê

| Preciso mexer em… | Vá para |
| --- | --- |
| conexão/estado/reconexão | `src/device.js` |
| assinatura e chamadas da Tuya | `src/tuya-cloud.js` |
| leitura/comando pela nuvem | `src/cloud-link.js` |
| unidades (W, V, A, kWh) | `src/normalize.js` |
| mapa de DPs do modelo | `src/dps.js` + `config.json` |
| rotas/WS | `src/server.js` |
| UI (PWA) | `public/` |
| app Android nativo (Compose, automações, geofencing) | `android/` — veja `android/README.md` |

## Invariantes (quebrar qualquer um destes vira bug)

1. Todo `new TuyAPI(...)` precisa de `client.on('error', () => {})` — sem isso
   o processo morre.
2. Toda chamada `get()`/`connect()` precisa de timeout (`withTimeout`).
3. O plug aceita **uma** conexão TCP; com o Smart Life aberto a LAN falha.
4. A assinatura da Tuya inclui o `access_token`:
   `HMAC(client_id + token + t + nonce + stringToSign)`.
5. Nunca detecte data center pelo token — só por uma chamada de dispositivo.
6. `1106` = aparelho não vinculado; `28841107` = data center errado.
7. Não logue `local_key`, `clientSecret` ou token.
8. O mapa de DPs nunca é hardcoded: vem da especificação no `setup`.

## Estado atual (2026-11-06)

- Aparelho: `ebdd7b1cc6f66458f6abta`, IP `192.168.0.4`, protocolo **3.5**.
- DPs: `1` switch, `20` energia ×10⁻³, `21` corrente mA, `22` potência ×10⁻¹,
  `23` tensão ×10⁻¹.
- Nuvem: projeto `SmartPlug`, data center **us** (`openapi.tuyaus.com`),
  IoT Core trial até 2026-12-06.
- Última verificação: plug fora da LAN → servidor rodando em `via: "cloud"`.
- Verificação rápida: `curl http://127.0.0.1:3000/api/state`.

## Como validar uma mudança

1. `npm run mock` e abra a UI: botão alterna, métricas e gráfico renderizam.
2. `node --check src/*.js` em tudo que tocar.
3. Com hardware: `curl -X POST /api/power -d '{"on":true}'` e conferir
   `on`/`power` no `/api/state` (e o relé de verdade).
4. Se mexer na nuvem: `node src/setup.js --probe …` deve listar os DCs.

## Não faça

- Não reimplemente o protocolo Tuya: `tuyapi` já cobre 3.1–3.5.
- Não troque `shouldWaitForResponse: false` por `true` sem medir: o aparelho
  demora e o comando trava o `set` por ~5 s.
- Não versione `config.json`.
- Não rode `setup` só para "testar": ele sobrescreve `device`, `dps`, `codes`.
- Não adicione dependência pesada no front (a PWA é vanilla de propósito).

## Pendências conhecidas

Autostart, redescoberta de IP, acúmulo de energia, timer (DP 9), testes
automatizados. Detalhes e prioridade em `docs/ARQUITETURA.md` §14.
