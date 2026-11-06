# Arquitetura e guia de manutenção

Documento técnico de referência. Se você é uma IA (ou pessoa) assumindo este
projeto sem contexto, **leia este arquivo inteiro antes de mexer no código**.
O `AGENTS.md` na raiz tem o resumo operacional; o `README.md` tem o uso.

> Estado atual do ambiente, decisões e armadilhas já pagas estão nas seções
> 10 a 13. Não repita a investigação: os erros da API da Tuya e do protocolo
> local já foram depurados e estão documentados.

---

## 1. Resumo

Servidor Node.js que controla **um** smart plug Tuya por dois caminhos:

| Caminho | Transporte | Quando entra |
| --- | --- | --- |
| **Local (LAN)** | TCP 6668, protocolo Tuya binário | Preferencial, quando o servidor alcança o plug |
| **Nuvem** | HTTPS `openapi.tuyaus.com` | Fallback automático (`mode: auto`) ou modo exclusivo (`mode: cloud`) |

Na frente dele, uma **PWA** (HTML/CSS/JS sem framework) consome uma API HTTP e
um WebSocket. O app nunca fala com o plug: fala com o servidor.

O objetivo do projeto é controle próprio, com a mesma cobertura de rede do
app oficial (Smart Life), mas sem depender dele para o dia a dia.

---

## 2. O aparelho (fatos verificados, não suposições)

- Marca/modelo: **WGHINE T34-Smart Plug+** (produto Tuya genérico, 16 A, com medição).
- `Device ID`: `ebdd7b1cc6f66458f6abta`.
- Protocolo local: **3.5**.
- IP local: `192.168.0.4` (DHCP — pode mudar; ver §11).
- O plug aceita **uma única conexão TCP**; com o Smart Life aberto ele
  derruba/recusa a conexão do servidor.

### 2.1 Data points (o ponto mais importante do projeto)

Este modelo **não usa** o mapa "clássico" (`17/18/19/20`). Mapa real, obtido em
`GET /v1.1/devices/{id}/specifications`:

| DP | code | tipo | escala | significado |
| --- | --- | --- | --- | --- |
| 1 | `switch_1` | bool | — | relé |
| 9 | `countdown_1` | int | s | timer |
| 20 | `add_ele` | int | 10⁻³ | energia acumulada (kWh) |
| 21 | `cur_current` | int | 10⁰ | corrente em **mA** |
| 22 | `cur_power` | int | 10⁻¹ | potência em **0,1 W** |
| 23 | `cur_voltage` | int | 10⁻¹ | tensão em **0,1 V** |
| 24 | `test_bit` | int | — | teste de fábrica |
| 29 | `fault` | bitmap | — | falhas (`ov_cr`, `ov_vol`, …) |
| 38 | `relay_status` | enum | — | estado ao energizar |
| 42/43/44 | `random_time` / `cycle_time` / `switch_inching` | string | — | agendamentos internos |

**Consequência de projeto:** o mapa nunca deve ser hardcoded. O `setup` lê a
especificação na nuvem e grava `dps`, `scaling` e `codes` no `config.json`.
Se você trocar de aparelho, rode o `setup` de novo.

### 2.2 Conta de desenvolvedor Tuya (usada só para setup e modo nuvem)

- Projeto: `SmartPlug`, data center **Western America** (`us`,
  `https://openapi.tuyaus.com`).
- O data center é definido na criação do projeto e **não pode ser movido**.
- Permissão do aparelho no projeto: `Read` — suficiente para ler status **e**
  enviar comandos (verificado).
- `IoT Core` em trial até **2026-12-06**; expirado, o `setup` e o modo nuvem
  param de funcionar até renovar no console.

---

## 3. Visão de arquitetura

```
                     ┌──────────────────────────────────────────┐
   celular (PWA)     │  src/server.js                           │
   HTTP  :3000 ─────►│  Express + WebSocket  /ws                │
   WS    /ws         │        │                                 │
                     │        ▼                                 │
                     │  src/device.js  (SmartPlug, EventEmitter)│
                     │   ├─ LAN  ── tuyapi ── TCP 6668 ──────────┼──► plug
                     │   └─ nuvem ─ src/cloud-link.js ─ HTTPS ───┼──► openapi.tuyaus.com
                     │        │                                 │
                     │        ▼                                 │
                     │  src/normalize.js  (DP cru → unidade)    │
                     └──────────────────────────────────────────┘
```

Camadas:

1. **`server.js`** — HTTP/WS, autenticação opcional, estáticos, ciclo de vida.
2. **`device.js`** — orquestra LAN + nuvem, máquina de estados, reconexão,
   histórico em memória, eventos `state`.
3. **`cloud-link.js`** — cliente REST da Tuya (token, status, comando).
4. **`tuya-cloud.js`** — assinatura HMAC, regiões, descoberta de DC, specs.
5. **`normalize.js`** — converte DPs crus em `{on, power, voltage, current, energy}`.
6. **`dps.js`** — traduz a especificação do produto em `{dps, scaling, codes}`.
7. **`config.js`** — carrega/grava `config.json`, defaults, override por env.

---

## 4. Mapa de arquivos

| Arquivo | Responsabilidade | Cuidados |
| --- | --- | --- |
| `src/server.js` | Express, rotas, WS, broadcast, shutdown | `setViewers` controla a frequência da nuvem |
| `src/device.js` | Estado, LAN, fallback, reconexão, histórico | coração do projeto; ver §5 e §11 |
| `src/cloud-link.js` | Token com cache, status, `setPower` | nunca logar token/secret |
| `src/tuya-cloud.js` | Assinatura, `call`, `detectRegion`, `getSpecifications` | assinatura inclui `access_token` (§7.1) |
| `src/normalize.js` | Regras de unidade | escala explícita > heurística |
| `src/dps.js` | `code` → campo + escala | só cobre os campos usados |
| `src/config.js` | Defaults, merge profundo, env | `merge` é recursivo; cuidado ao passar objetos parciais |
| `src/setup.js` | Assistente CLI | único lugar que busca a `local_key` e a especificação |
| `src/scan.js` | Descoberta UDP | precisa de broadcast na rede |
| `public/*` | PWA | `sw.js` cacheia casca; navegação é network-first |
| `tools/make-icons.js` | Gera PNGs sem dependência | roda com `npm run icons` |

Dependências de runtime: `express@^5`, `tuyapi@^7.7`, `ws@^8`.
Não há banco de dados: todo estado é memória + `config.json`.

---

## 5. Fluxos

### 5.1 Inicialização (`server.js` → `plug.start()`)

```
start()
 ├─ mock?            → loop simulado, sem rede
 ├─ mode === cloud?  → _pollCloud() imediato + timer (15 s / 90 s)
 └─ senão            → _connect() (LAN), com fallback para nuvem em caso de erro
```

`_connect()`:

1. `_resolveIp()` — se `device.ip` estiver vazio, escuta UDP 6666/6667 por 12 s.
2. `_resolveVersion()` — se `version: "auto"`, testa a última versão confirmada
   em memória e depois `3.3 → 3.4 → 3.5 → 3.2 → 3.1`.
3. Abre o cliente `tuyapi`, registra listeners, conecta (timeout 8 s).
4. `_poll()` imediato e timer de 5 s (`polling.intervalMs`).
5. Em sucesso: para o polling da nuvem e marca `via: 'local'`.

### 5.2 Leitura

- **LAN:** `client.get({schema:true})` a cada 5 s, além de eventos `data` e
  `dp-refresh` do próprio aparelho.
- **Nuvem:** `GET /v1.0/devices/{id}/status` a cada 15 s (com alguém olhando)
  ou 90 s (ocioso).
- Tudo passa por `normalize()` e cai em `_applyPayload()`, que atualiza
  `state`, empurra um ponto no histórico (máx. 1 a cada 1,5 s) e emite `state`
  com throttle de 150 ms.

### 5.3 Comando (`POST /api/power`)

```
setPower(on)
 ├─ mock           → simula
 ├─ LAN conectada  → client.set({dps: dps.switch, set: on, shouldWaitForResponse:false})
 │                    → espera 350 ms → _poll() para confirmar
 └─ senão, nuvem   → POST /v1.0/devices/{id}/commands {commands:[{code, value}]}
                      → espera 700 ms → _pollCloud()
```

Atualização otimista do estado antes do envio, com rollback em erro.

### 5.4 Máquina de estados (LAN)

```
        ┌────────────┐  conecta   ┌───────────┐
        │  offline   │───────────►│  online   │
        └────────────┘            └───────────┘
              ▲                        │ erro/socket fecha
              │  backoff 1→30 s        ▼
              └────────────────── _handleDown()
```

- `_handleDown()` **não** marca offline se `via === 'cloud'` (a nuvem está
  entregando dados) — isso evita o app "piscar" offline.
- Cada falha agenda reconexão com backoff exponencial (1, 2, 4 … 30 s).
- Se `cloud` estiver configurada, o polling da nuvem começa em paralelo.
- Quando a LAN volta, `_stopCloudPolling()` e `via` volta para `local`.

### 5.5 `setup` (uma vez por aparelho)

```
1. perguntas: device id, ip, client id, secret
2. detectRegion()      → testa cada DC com uma chamada real de dispositivo
3. getDevice()         → local_key + nome
4. getSpecifications() → dps + scaling + codes
5. grava config.json (device, dps, scaling, codes, cloud)
6. testConnection()    → tenta 3.3/3.4/3.5 localmente e mostra o status
```

Flags: `--id`, `--ip`, `--region`, `--client-id`, `--client-secret`,
`--probe` (só diagnóstico), `--no-cloud` (não guarda o secret).

---

## 6. Protocolo local da Tuya (o que o `tuyapi` resolve)

- TCP **6668**, payload cifrado com AES-128-ECB usando a `local_key` de 16 chars.
- Descoberta por broadcast UDP 6666/6667/7000 (traz `id`, `ip`, versão).
- 3.4/3.5 usam chave de sessão derivada no handshake; 3.5 tem framing próprio.
- O `tuyapi` cuida disso; **não reimplemente** o protocolo sem necessidade.
- A `local_key` muda se o aparelho for removido e pareado de novo.

Comandos relevantes do `tuyapi` usados: `find()`, `connect()`, `get({schema})`,
`set({dps, set, shouldWaitForResponse})`, `disconnect()`, eventos
`data`, `dp-refresh`, `connected`, `disconnected`, `error`.

---

## 7. API da Tuya (nuvem)

### 7.1 Assinatura — a parte que a documentação oficial omite

```
stringToSign = METHOD + "\n" + sha256(body) + "\n\n" + path?query
material     = client_id + (access_token || "") + t + nonce + stringToSign
sign         = HMAC-SHA256(material, secret).hex().toUpperCase()
```

- Em chamadas **autenticadas**, o `access_token` entra no material do HMAC
  logo após o `client_id`. Sem isso a Tuya responde `1004 sign invalid` em
  qualquer endpoint de negócio (o `/v1.0/token` continua funcionando, o que
  engana).
- `t` em milissegundos, `nonce` UUID, headers `client_id`, `sign`, `t`,
  `sign_method: HMAC-SHA256`, `nonce` (+ `access_token` quando houver).
- O corpo do POST precisa ser **exatamente** o mesmo string assinado.

### 7.2 Data centers

| Nome | Código | Endpoint |
| --- | --- | --- |
| Western America | `us` / `AZ` | `https://openapi.tuyaus.com` |
| Eastern America | `us-east` / `UE` | `https://openapi-ueaz.tuyaus.com` |
| Central Europe | `eu` | `https://openapi.tuyaeu.com` |
| Western Europe | `eu-west` / `WE` | `https://openapi-weaz.tuyaeu.com` |
| China | `cn` | `https://openapi.tuyacn.com` |
| India | `in` | `https://openapi.tuyain.com` |
| Singapore | `sg` | `https://openapi-sg.iotbing.com` |

O token é emitido por **qualquer** endpoint, então detectar o DC "pelo token"
é falso positivo. `detectRegion()` faz uma chamada real de dispositivo e
classifica a resposta (ver §7.4).

### 7.3 Endpoints usados

| Uso | Método e rota |
| --- | --- |
| token | `GET /v1.0/token?grant_type=1` |
| detalhe do aparelho (traz `local_key`) | `GET /v1.0/devices/{id}` |
| especificação (DPs) | `GET /v1.1/devices/{id}/specifications` |
| status | `GET /v1.0/devices/{id}/status` |
| comando | `POST /v1.0/devices/{id}/commands` |

### 7.4 Códigos de erro já vistos

| Código | Mensagem | Significado prático |
| --- | --- | --- |
| 2009 | `clientId is invalid` | credencial errada (ou caractere perdido na cópia) |
| 1004 | `sign invalid` | assinatura errada (quase sempre: falta o `access_token`) |
| 1106 | `permission deny` | aparelho não vinculado ao projeto |
| 28841107 | `data center is suspended` | projeto não está neste DC |
| 2007 | `cross-region access is not allowed` | IP fora da região do endpoint |

---

## 8. Contrato HTTP/WS

### `GET /api/state` (auth opcional)

```json
{
  "online": true, "on": false, "power": 0, "voltage": 238.9, "current": 0,
  "energy": 0.005, "latency": 400, "name": "T34-Smart Plug+",
  "ip": "192.168.0.4", "version": "3.5", "via": "local",
  "updatedAt": 1793971234567, "lastError": null, "raw": { "1": false, "23": 2389 },
  "mock": false, "history": [{ "t": 1793971200000, "p": 49.8 }]
}
```

`via`: `"local"` | `"cloud"` | `null`. `raw`: DPs crus (útil para calibrar).

### Rotas

| Rota | Auth | Descrição |
| --- | --- | --- |
| `GET /api/state` | sim | snapshot completo |
| `GET /api/info` | sim | nome, id, ip, versão, mapa de DPs |
| `POST /api/power` | sim | corpo `{"on": true|false}` |
| `GET /api/health` | não | `{ok, mock, uptime}` |
| `WS /ws` | `?token=` | push `{type:"state", state}` a cada mudança |

Auth = header `x-token` (ou `?token=`) quando `server.token` está definido.

---

## 9. Configuração (`config.json`)

Gerado pelo `setup`; **contém segredos** (local_key e, por padrão, o secret da
Tuya). Nunca versionar — já está no `.gitignore`.

```json
{
  "mode": "auto",
  "device": { "id": "...", "key": "<local_key 16 chars>", "ip": "192.168.0.4",
              "version": "auto", "name": "T34-Smart Plug+" },
  "cloud":  { "enabled": true, "clientId": "...", "clientSecret": "...", "region": "us" },
  "dps":     { "switch": 1, "power": 22, "voltage": 23, "current": 21, "energy": 20 },
  "scaling": { "power": 0.1, "voltage": 0.1, "current": null, "energy": 0.001 },
  "codes":   { "switch": "switch_1", "power": "cur_power", "voltage": "cur_voltage",
               "current": "cur_current", "energy": "add_ele" },
  "server":  { "port": 3000, "host": "0.0.0.0", "token": "" },
  "polling": { "intervalMs": 5000, "historyPoints": 120 }
}
```

`scaling` ausente/`null` cai nas heurísticas de `normalize.js`
(tensão > 1000 → ÷10, corrente > 60 → ÷1000, energia ÷100).

Overrides por ambiente: `SMARTPLUG_MODE`, `SMARTPLUG_CONFIG`, `SMARTPLUG_TOKEN`,
`TUYA_ID`, `TUYA_KEY`, `TUYA_IP`, `TUYA_VERSION`, `TUYA_NAME`, `PORT`.

---

## 10. Decisões e por quês

| # | Decisão | Motivo |
| --- | --- | --- |
| 1 | `tuyapi` (Node) em vez de `tinytuya` (Python) | a máquina não tinha Python; Node 24 já estava instalado |
| 2 | LAN como caminho principal | ~400 ms de leitura e comando imediato, sem cota |
| 3 | Nuvem como fallback e modo remoto | cobre plug fora da LAN e servidor hospedado fora de casa |
| 4 | Mapa de DPs vindo da especificação | o T34 não segue o mapa "clássico"; hardcode quebra em qualquer modelo novo |
| 5 | Escala explícita tem prioridade sobre heurística | evita divisão dupla quando o `scale` oficial é conhecido |
| 6 | Probe de versão falha rápido quando o TCP abre e o aparelho cala | aparelho ocupado (app aberto) não é "versão errada"; 50 s de tentativa atrasavam o fallback |
| 7 | Sem banco de dados | um plug, um servidor; histórico em memória basta |
| 8 | PWA sem framework | zero build, um arquivo JS, abre no celular sem loja |
| 9 | `online` não vai a `false` se a nuvem está servindo | evita o app piscar offline durante o retry da LAN |

---

## 11. Armadilhas conhecidas (regras de ouro)

1. **Sempre registre `client.on('error', …)`** em qualquer instância `TuyAPI`.
   Sem listener, o `EventEmitter` lança e **derruba o processo** (aconteceu no
   `setup`).
2. **Sempre envolva `get()`/`connect()` em timeout.** Se o socket cai, a
   promise do `tuyapi` pode nunca resolver — o Node encerra com código 0 e
   nenhuma saída, o que parece "travamento silencioso".
3. **Uma conexão TCP por vez.** Com o Smart Life aberto, a LAN falha
   (`timeout` ou `ECONNREFUSED`). É comportamento do aparelho.
4. **`1106` ≠ `28841107`.** O primeiro é aparelho não vinculado; o segundo é
   projeto em outro data center. O `detectRegion` depende dessa distinção.
5. **Token válido não indica data center correto.** Só uma chamada de negócio
   indica.
6. **Data center é imutável por projeto.** Se o console mostrar outro DC, o
   projeto precisa ser recriado (foi o que resolveu neste caso).
7. **Depois de criar/mover projeto, a propagação demora minutos** e o erro
   muda de `28841107` para `1106` quando "assenta".
8. **A `local_key` muda** ao remover/parear o aparelho de novo → rodar `setup`.
9. **`scale` da Tuya é potência de 10 negativa:** `valor_real = cru × 10⁻ˢᶜᵃˡᵉ`.
10. **Não logue** `local_key`, `clientSecret` nem `access_token`.
11. **No Windows/PowerShell**, `Test-NetConnection` pode levar ~20 s em host
    morto; evite em loop. E `stderr` do Node aparece como erro do PowerShell —
    não confunda com falha de comando.
12. **`config.json` é a única fonte de verdade** do aparelho; o `setup`
    sobrescreve campos — cuidado ao rodá-lo para "testar".

---

## 12. Como testar

```bash
npm run mock          # UI sem hardware (porta 3000)
npm run scan          # descobre Device ID / IP na LAN
node src/setup.js --probe --id <id> --client-id <id> --client-secret <secret>
npm start             # produção local

# caminho nuvem isolado (PowerShell):
$env:SMARTPLUG_MODE='cloud'; $env:PORT='3100'; npm start

# caminho nuvem isolado (bash):
SMARTPLUG_MODE=cloud PORT=3100 npm start
```

Checklist manual (sem suíte automatizada):

- [ ] `GET /api/state` responde `online:true` e `via` coerente.
- [ ] `POST /api/power {"on":true}` muda o relé e `power` sobe.
- [ ] Fechar/abrir o app do celular derruba e restaura o `via:local`.
- [ ] `npm run mock` renderiza a UI e o botão alterna.
- [ ] `sw.js` e `manifest` respondem 200; ícones existem.

**Não há testes automatizados.** Se for adicionar, os alvos de maior valor são
`normalize.js` (fixtures de DPs), `dps.js` (spec real do §2.1) e
`cloud-link.js` (mock de `fetch`).

---

## 13. Estado do ambiente em 2026-11-06

- Windows, Node `v24.19.0`, projeto em `D:\Documentos\projects\SmartPlug`.
- Plug configurado (`config.json` presente), protocolo 3.5 confirmado.
- Na última verificação o plug **não respondia na LAN** (`ping` e TCP 6668
  falhavam; IP do PC mudou de `.18` para `.6`), então o servidor estava em
  `via: "cloud"` — exatamente o cenário de fallback.
- Modo nuvem validado ponta a ponta: comando `switch_1` mudou o relé.
- `server.token` vazio (uso em rede local).
- Segredo da Tuya exposto em capturas de tela durante o setup — **rotacionar**
  no console quando conveniente.

---

## 14. Backlog priorizado

1. **Autostart** (Agendador de Tarefas/NSSM) — hoje o servidor só roda enquanto
   o terminal está aberto.
2. **Redescobrir IP** quando a LAN falha repetidamente (hoje o IP é fixo no
   config) e sugerir IP reservado no roteador.
3. **Acumular energia no servidor** — o `add_ele` do firmware zera em alguns
   ciclos; persistir um contador próprio resolve.
4. **Agendamentos/timer** usando o DP 9 (`countdown_1`), que o aparelho já tem.
5. **Testes automatizados** (§12).
6. **Log com menos ruído** — o retry de 30 s imprime uma linha por tentativa.
7. **Docker/ serviço** para hospedar em VPS sem Node instalado à mão.
8. **Renovação automática de token** em `1010/1011` já existe; falta tratar
   expiração do trial do IoT Core com mensagem clara no app.

---

## 14.1 Acesso remoto (hospedar fora de casa)

O servidor em `mode: cloud` não precisa da LAN nem da `local_key`.

1. `node tools/export-cloud-env.js` → imprime `SMARTPLUG_CONFIG_JSON` (base64,
   com segredos) e o token de acesso.
2. Hospede o repositório (sem `config.json`) num serviço Node com HTTPS
   (Railway, Render, Fly…): start `npm start`, variável `SMARTPLUG_CONFIG_JSON`.
3. Abra a URL pública no celular, informe o token e instale a PWA.

Alternativa sem hospedagem: manter o PC ligado e expor com Cloudflare Tunnel
ou Tailscale (mantém LAN como caminho principal).

## 15. Segurança

- `config.json` guarda `local_key` + `clientSecret`; permissão 600 no `setup`.
- API sem TLS por padrão: use Tailscale/Cloudflare Tunnel para sair da rede.
- Se expor publicamente, defina `server.token` e coloque HTTPS na frente.
- Não há rate limit nas rotas; para exposição pública, adicione.
