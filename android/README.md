# Smart Plug — app Android nativo

App Android (Kotlin + Jetpack Compose) para controlar um smart plug **Tuya** com monitoramento
elétrico, controle manual e **automações personalizadas** (horário, localização, consumo, estado
do plug) que continuam funcionando em segundo plano.

> Este app é independente do servidor Node da raiz do repositório (`../src`). Os dois falam com a
> mesma Tuya Cloud; o app não precisa do servidor.

## 1. Arquitetura

Módulo único `app`, organizado em pacotes com dependências em uma só direção
(`features → domain ← data/integrations`):

```
com.smartplug.app
├─ domain/            Kotlin puro (sem Android): modelos, condições, motor, interfaces
│  ├─ model/          Automation, Condition (sealed), ConditionNode, PlugState, ConditionText…
│  ├─ engine/         AutomationEngine, avaliadores, executores de ação, TimeBoundaries
│  └─ gateway/        PlugGateway, AutomationStore, ExecutionLog, LocationStateSource, PlugMemory
├─ integrations/tuya/ TuyaApiClient (OkHttp), TuyaSigner (HMAC), TuyaPlugGateway, DeviceProfile
├─ data/              Room (entidades, DAOs, repositórios) e DataStore + Keystore (AppPrefs)
├─ location/          GeofenceManager, GeofenceReceiver, LocationTracker, PermissionChecker
├─ workers/           WorkScheduler + EngineWorker (WorkManager)
├─ receivers/         BootReceiver (boot, atualização do app, mudança de fuso/hora)
├─ notifications/     AutomationNotifier
├─ features/          dashboard · automations (lista, editor em 4 etapas, modelos) · locations · history · settings
├─ ui/                tema Material 3, componentes reutilizáveis, navegação
└─ di/                módulos Hilt
```

**Pontos de extensão** (sem tocar no restante do código):

| Quero adicionar… | Faça |
| --- | --- |
| nova **condição** | subclasse `@Serializable` em `Conditions.kt` + um `ConditionEvaluator` em `AppModule` (`@IntoSet`) + texto em `ConditionText` + tipo em `ConditionCatalog`/`ConditionDialog` |
| nova **ação** | subclasse de `ActionSpec` + um `ActionExecutor` em `AppModule` (`@IntoSet`) |
| outro **backend** de plug | implementar `PlugGateway` (o motor e as telas não mudam) |

### Automation Engine (`domain/engine`)

Independente de UI e de Android. Regras principais:

- Dispara **só na transição falso → verdadeiro** (ou por recorrência explícita), nunca a cada verificação.
- O primeiro resultado de uma automação é só **linha de base**: se a regra já é verdadeira ao salvar, ela age na próxima transição (não liga na hora).
- Lógica em **3 valores** (verdadeiro/falso/**desconhecido**). Dado indisponível (sem localização, plug offline, sem leitura) ⇒ desconhecido ⇒ **não executa e não muda o estado anterior**. Ausência de localização nunca vale como "fora de casa".
- Árvore de condições com **grupos AND/OR aninhados** e **tempo de permanência** por condição (persistido; sobrevive a reinício).
- Eventos (`Chegando`, `Saindo`, `Mudança de estado`) valem só no instante em que acontecem.
- **Proteções:** intervalo mínimo entre execuções; estado real lido antes de agir (ligar quando já está ligado = ignorado como duplicidade); `Mutex` (uma rodada por vez); cada automação age no máximo uma vez por rodada (corta ciclos entre automações); limite de 6 execuções/hora por automação.
- Falha de comunicação **não consome a transição**: a próxima rodada tenta de novo (com novas tentativas internas e `Result.retry()` no WorkManager).
- Registra no histórico: condições avaliadas, resultado, ação, estado antes/depois, retorno da Tuya e erros.

## 2. Tecnologias

Kotlin 2.4 · Jetpack Compose + Material 3 (BOM 2026.09) · Navigation Compose · MVVM + `StateFlow` ·
Hilt 2.60 · Room 2.8 · DataStore 1.3 · WorkManager 2.12 · Play Services Location 21.4 (Geofencing) ·
OkHttp 5 · kotlinx.serialization · osmdroid 6.1 (mapa OpenStreetMap, sem chave de API) ·
AGP 9.5 / Gradle 9.8 · JDK 17 · compileSdk 37 · targetSdk 36 · **minSdk 26 (Android 8.0)**.

## 3. Integração Tuya e o que você precisa fornecer

**Escolha:** Tuya **Cloud OpenAPI** (assinada com HMAC-SHA256), a mesma já validada com o seu aparelho
pelo servidor Node. O *Tuya Smart Device SDK* foi descartado: exige AppKey/AppSecret de um projeto
"App SDK", imagem de segurança vinculada ao pacote/SHA-256 da assinatura e contas próprias do SDK,
sem ganho para o caso de uso. Endpoints usados (todos documentados): `GET /v1.0/token`,
`GET /v1.0/devices/{id}` (online + status), `GET /v1.1/devices/{id}/specifications`
(DPs, escalas, unidades), `POST /v1.0/devices/{id}/commands`.

**Você precisa informar no app (Ajustes → Conta Tuya Cloud):**

1. **Client ID** e **Client Secret** do projeto em <https://iot.tuya.com> (Cloud → seu projeto → Overview).
2. **Device ID** do plug (Cloud → Devices). O aparelho precisa estar vinculado ao projeto
   (erro `1106` = não vinculado).
3. **Data center** do projeto (erro `28841107` = data center errado). O seu: Western America (`us`).
4. O serviço **IoT Core** ativo no projeto (o trial atual vence em **2026-12-06**; depois disso o app deixa de controlar o plug até renovar).

Segurança: **nada de credencial no código-fonte**. O app as guarda criptografadas (AES-256-GCM, chave
no Android Keystore), fora de backup/transferência de aparelho. O mapa de DPs (`switch_1`, `cur_power`…
e escalas) é descoberto da especificação do produto na configuração, não é fixo.

Latência/limites: leitura a cada 15 s com a tela de início aberta; comandos confirmam o estado lido
depois (a nuvem leva 1–3 s). Tudo passa pela mesma `PlugGateway` (controle manual e automações).

## 4. Permissões Android

| Permissão | Para quê | Quando é pedida |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | falar com a Tuya e baixar tiles do mapa | instalação |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | geofencing e "usar minha localização" | contextual (aba Locais / editor de local) |
| `ACCESS_BACKGROUND_LOCATION` | geofencing com o app fechado | contextual, depois da de primeiro plano |
| `RECEIVE_BOOT_COMPLETED` | restaurar agendamentos e geofences após reiniciar | instalação |
| `POST_NOTIFICATIONS` (Android 13+) | avisos opcionais de execução | só ao ligar a opção em Ajustes |

Não usa Foreground Service, `SCHEDULE_EXACT_ALARM` nem `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## 5. Estratégia de execução em segundo plano

- **WorkManager** periódico (mín. 15 min, configurável 15/30/60) com rede obrigatória: reavalia horário e consumo.
- **Despertar agendado**: ao fim de uma permanência ou na próxima virada de horário, o motor agenda um trabalho único (`engine-wake`).
- **Geofencing** (`ENTER`/`EXIT`, sem GPS contínuo): o `GeofenceReceiver` roda o motor na hora (`goAsync`, 9 s); se estourar, o WorkManager assume com os mesmos eventos. Sem gatilho inicial, para não gerar "chegou" falso ao registrar. A posição conhecida vem de `lastLocation` (gratuita) e de uma leitura única ao registrar.
- **Reinício/atualização/fuso:** `BootReceiver` reagenda o periódico, registra os geofences de novo e roda uma rodada de recuperação; o estado (última execução, permanências, status dos locais) está no Room, então não há disparo duplicado.
- **Não há Foreground Service**: nada exige execução contínua; evita notificação permanente e consumo de bateria.

## 6. Limitações (leia antes de confiar)

- O Android **pode atrasar ou bloquear** o trabalho em segundo plano (Doze, App Standby, economia de bateria, fabricantes como Xiaomi/Samsung/Huawei/Oppo). O mínimo do WorkManager é 15 min: "desligar após 10 min sem consumo" tem precisão de alguns minutos, não de segundos. Orientações em Ajustes → Execução em segundo plano.
- Sem a permissão de localização **em segundo plano**, as automações de local só funcionam com o app aberto. No Android 11+ é preciso escolher "Permitir o tempo todo" nas configurações do app.
- Geofences menores que ~100 m são pouco confiáveis; o Android pode perdê-los (a verificação periódica reconcilia com a última posição, e há zona de incerteza para evitar falsos positivos).
- Horário "igual a" usa uma **janela de tolerância** (padrão 15 min), porque o sistema não garante o minuto exato.
- Comandos dependem de internet e do plug online na nuvem Tuya; sem isso a automação registra falha e tenta de novo.
- Mapa: tiles do OpenStreetMap (uso pessoal/leve; para uso intenso troque por um provedor próprio).
- Não há modo LAN no app (só nuvem). O trial do IoT Core expira em 2026-12-06.

## 7. Testes

**55 testes unitários (JVM) + 23 instrumentados (aparelho real), todos aprovados.**

Unitários (`./gradlew testDebugUnitTest`):

- Motor (30): horário, virada de dia, dias úteis, mudança de fuso; localização (chegar/sair/dentro, **localização indisponível**); AND/OR/desconhecido; consumo e **tempo de permanência** (reinício da contagem); estado do plug; **duplicidade**, intervalo mínimo, recorrência, **ciclos**, limite por hora; **falha da Tuya**, **falta de internet** (tentativas e retomada); desabilitada; **execuções simultâneas**; executar agora.
- Tuya (17): assinatura contra vetores gerados pelo servidor Node que já controla o plug; conversão de unidades com a especificação real do T34; gateway contra servidor HTTP falso (token reaproveitado e renovado em `1010`, `1004`/`1106`, sem rede, timeout, não configurado, segredo fora de `toString`).
- Domínio (8): serialização JSON de todas as condições/ações (persistência), árvore do editor, validação, modelos prontos, textos, próximo despertar.

Instrumentados (`./gradlew connectedDebugAndroidTest`, aparelho conectado): Room em disco com
criação/edição/exclusão/duplicação, **recuperação após fechar e reabrir o banco (equivale a reinício)**,
histórico sobrevivendo à exclusão; Keystore e segredos sem texto claro em disco; WorkManager
(periódico sem duplicar, despertar, evento de geofence); fluxo de UI de criar/excluir automação, navegação
por todas as abas, validações do editor, diálogo de condições, tela de revisão SE/ENTÃO.

Executados no **Samsung Galaxy J4 Core (SM-J410G, Android 8.1, API 27)** — o piso do `minSdk`.
Não executados: reinício físico do aparelho (cobre-se o equivalente em dados e no receiver, mas o
`BOOT_COMPLETED` real não foi disparado), geofence real saindo/entrando de casa, e comando real ao plug
pelo app (o caminho Tuya foi validado só por servidor falso + o servidor Node, não pelo app no celular).
Testes em outras versões do Android (13/14/15/16) não foram feitos.

## 8. Compilar

Requisitos: JDK 17, Android SDK (plataforma 37), Gradle wrapper incluso.

```bash
cd android
./gradlew testDebugUnitTest          # testes unitários
./gradlew assembleDebug              # APK de depuração
./gradlew assembleRelease            # APK de release (assinado se keystore.properties existir)
./gradlew connectedDebugAndroidTest  # testes no aparelho conectado
```

Assinatura de release: crie `keystore.properties` (ignorado pelo Git) com `storeFile`, `storePassword`,
`keyAlias`, `keyPassword`. **Guarde o keystore:** sem ele não é possível atualizar o app instalado.

## 9. Instalar

O APK de release é `android/dist/SmartPlug-1.0.0.apk` (também em `app/build/outputs/apk/release/`).
Com o celular conectado e depuração USB ativa:

```bash
adb install -r dist/SmartPlug-1.0.0.apk
```

Ou copie o arquivo para o celular e abra-o (permita "instalar apps desconhecidos"). Depois: Ajustes →
Conta Tuya Cloud → Testar e salvar; em Locais, cadastre "Minha casa" e conceda as permissões.
