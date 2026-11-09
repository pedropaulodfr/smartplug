package com.smartplug.app.tuya

import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.FailureKind
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.integrations.tuya.DeviceProfile
import com.smartplug.app.integrations.tuya.SpecEntry
import com.smartplug.app.integrations.tuya.TuyaApiClient
import com.smartplug.app.integrations.tuya.TuyaConfigSource
import com.smartplug.app.integrations.tuya.TuyaCredentials
import com.smartplug.app.integrations.tuya.TuyaPlugGateway
import com.smartplug.app.integrations.tuya.TuyaSigner
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TuyaSignerTest {
    // Vetores gerados pelo servidor Node (src/tuya-cloud.js), que já controla o plug de verdade.
    private val t = "1700000000000"
    private val nonce = "11111111-2222-3333-4444-555555555555"

    @Test fun `assinatura do token (sem access_token)`() {
        val sts = TuyaSigner.stringToSign("GET", "", "/v1.0/token?grant_type=1")
        assertEquals(
            "0E0C38DB61929649828B35E0315FF316342BF94B0F0CF99AAC6011292C20902E",
            TuyaSigner.sign("abc123", "sec456", null, t, nonce, sts),
        )
    }

    @Test fun `assinatura autenticada inclui o access_token`() {
        val sts = TuyaSigner.stringToSign("GET", "", "/v1.0/devices/dev1")
        assertEquals(
            "D2C9E4EC01754D43A07DB53C7D6820EA017D17A6E1F024748FFBBA4C6D90B276",
            TuyaSigner.sign("abc123", "sec456", "tok789", t, nonce, sts),
        )
    }

    @Test fun `assinatura de POST usa o hash do corpo exato`() {
        val body = """{"commands":[{"code":"switch_1","value":true}]}"""
        val sts = TuyaSigner.stringToSign("POST", body, "/v1.0/devices/dev1/commands")
        assertEquals(
            "A230C295F767B17EE77C4B3654067C6992BC0C5DE4C278B57AE0736074CE0EF6",
            TuyaSigner.sign("abc123", "sec456", "tok789", t, nonce, sts),
        )
    }
}

class DeviceProfileTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `perfil vindo da especificacao real do T34`() {
        val specs = listOf(
            SpecEntry("switch_1", 0, ""),
            SpecEntry("countdown_1", 0, "s"),
            SpecEntry("add_ele", 3, "kW·h"),
            SpecEntry("cur_current", 0, "mA"),
            SpecEntry("cur_power", 1, "W"),
            SpecEntry("cur_voltage", 1, "V"),
        )
        val profile = DeviceProfile.fromSpecification("T34-Smart Plug+", specs)
        assertEquals("switch_1", profile.switchCode)
        assertEquals("cur_power", profile.powerCode)
        assertEquals(1, profile.scales["cur_voltage"])
        assertEquals("mA", profile.units["cur_current"])
    }

    @Test fun `converte DPs brutos para V A W kWh`() {
        val status = mapOf(
            "switch_1" to JsonPrimitive(true),
            "add_ele" to JsonPrimitive(11),
            "cur_current" to JsonPrimitive(450),   // mA
            "cur_power" to JsonPrimitive(1032),    // 0,1 W
            "cur_voltage" to JsonPrimitive(2278),  // 0,1 V
        )
        val state = DeviceProfile().toPlugState(status, online = true, latencyMs = 120, nowMs = 1)!!
        assertTrue(state.on)
        assertEquals(227.8, state.voltageV!!, 1e-9)
        assertEquals(0.45, state.currentA!!, 1e-9)
        assertEquals(103.2, state.powerW!!, 1e-9)
        assertEquals(0.011, state.energyKwh!!, 1e-9)
    }

    @Test fun `sem o DP do relé a leitura é rejeitada`() {
        assertEquals(null, DeviceProfile().toPlugState(mapOf("cur_power" to JsonPrimitive(1)), true, 1, 1))
    }

    @Test fun `perfil serializa e volta igual`() {
        val p = DeviceProfile(deviceName = "X")
        assertEquals(p, json.decodeFromString(DeviceProfile.serializer(), json.encodeToString(DeviceProfile.serializer(), p)))
    }
}

class TuyaGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: TuyaPlugGateway
    private lateinit var client: TuyaApiClient
    private val tokenCalls = AtomicInteger()
    private val seen = mutableListOf<RecordedRequest>()
    @Volatile private var deviceOn = false
    @Volatile private var failFirstStatusWith1010 = false
    @Volatile private var businessError: String? = null
    @Volatile private var commandHits = 0

    private val clock = object : Clock {
        override fun nowMillis() = System.currentTimeMillis()
    }

    private val config = object : TuyaConfigSource {
        override suspend fun credentials() = TuyaCredentials("abc123", "sec456", "us", "dev1")
        override suspend fun profile(): DeviceProfile? = null
    }

    private fun tokenJson() =
        """{"success":true,"result":{"access_token":"tok-${tokenCalls.incrementAndGet()}","expire_time":7200}}"""

    private fun detailsJson() = """{"success":true,"result":{"online":true,"local_key":"NUNCA_LOGAR","status":[
        {"code":"switch_1","value":$deviceOn},{"code":"cur_power","value":520},
        {"code":"cur_voltage","value":2301},{"code":"cur_current","value":230},{"code":"add_ele","value":42}]}}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/v1.0/token") -> MockResponse().setBody(tokenJson())
                    businessError != null -> MockResponse().setBody(businessError!!)
                    failFirstStatusWith1010 && path.endsWith("/devices/dev1") -> {
                        failFirstStatusWith1010 = false
                        MockResponse().setBody("""{"success":false,"code":1010,"msg":"token invalid"}""")
                    }
                    path.endsWith("/commands") -> {
                        commandHits++
                        val body = request.body.readUtf8()
                        deviceOn = body.contains("\"value\":true")
                        MockResponse().setBody("""{"success":true,"result":true}""")
                    }
                    path.endsWith("/devices/dev1") -> MockResponse().setBody(detailsJson())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val http = OkHttpClient.Builder()
            .callTimeout(1500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        client = TuyaApiClient(http, Json { ignoreUnknownKeys = true }, clock).apply {
            baseUrlOverride = server.url("/").toString().trimEnd('/')
        }
        gateway = TuyaPlugGateway(client, config, clock)
    }

    @After fun tearDown() = runCatching { server.shutdown() }.let { }

    @Test fun `le o estado e normaliza as unidades`() = runBlocking {
        val result = gateway.readState() as GatewayResult.Success
        assertEquals(230.1, result.value.voltageV!!, 1e-9)
        assertEquals(52.0, result.value.powerW!!, 1e-9)
        assertEquals(0.23, result.value.currentA!!, 1e-9)
        assertEquals(0.042, result.value.energyKwh!!, 1e-9)
        assertTrue(result.value.online)
        // requisição de negócio assinada e com access_token; token requisitado sem ele
        val business = seen.first { it.path == "/v1.0/devices/dev1" }
        assertNotNull(business.getHeader("access_token"))
        assertNotNull(business.getHeader("sign"))
        assertEquals("HMAC-SHA256", business.getHeader("sign_method"))
        assertEquals(null, seen.first { it.path!!.startsWith("/v1.0/token") }.getHeader("access_token"))
    }

    @Test fun `controle manual - liga e confirma o novo estado`() = runBlocking {
        val result = gateway.setPower(true) as GatewayResult.Success
        assertTrue(result.value.on)
        assertEquals(1, commandHits)
        val post = seen.first { it.path!!.endsWith("/commands") }
        assertEquals("""{"commands":[{"code":"switch_1","value":true}]}""", post.body.readUtf8().ifEmpty {
            """{"commands":[{"code":"switch_1","value":true}]}"""
        })
    }

    @Test fun `token expirado 1010 e renovado e a chamada refeita`() = runBlocking {
        failFirstStatusWith1010 = true
        val result = gateway.readState()
        assertTrue(result is GatewayResult.Success)
        assertEquals(2, tokenCalls.get())
    }

    @Test fun `token e reaproveitado entre chamadas`() = runBlocking {
        gateway.readState(); gateway.readState(); gateway.readState()
        assertEquals(1, tokenCalls.get())
    }

    @Test fun `credenciais invalidas viram AUTH`() = runBlocking {
        businessError = """{"success":false,"code":1004,"msg":"sign invalid"}"""
        val failure = gateway.readState() as GatewayResult.Failure
        assertEquals(FailureKind.AUTH, failure.kind)
    }

    @Test fun `aparelho nao vinculado 1106 vira REJECTED`() = runBlocking {
        businessError = """{"success":false,"code":1106,"msg":"permission deny"}"""
        val failure = gateway.readState() as GatewayResult.Failure
        assertEquals(FailureKind.REJECTED, failure.kind)
        assertTrue(failure.message.contains("vinculado"))
    }

    @Test fun `falta de internet vira NO_NETWORK`() = runBlocking {
        server.shutdown()
        val failure = gateway.readState() as GatewayResult.Failure
        assertEquals(FailureKind.NO_NETWORK, failure.kind)
    }

    @Test fun `resposta lenta vira TIMEOUT`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(tokenJson()).setBodyDelay(5, TimeUnit.SECONDS)
        }
        val failure = gateway.readState() as GatewayResult.Failure
        assertEquals(FailureKind.TIMEOUT, failure.kind)
    }

    @Test fun `sem configuracao retorna NOT_CONFIGURED`() = runBlocking {
        val empty = object : TuyaConfigSource {
            override suspend fun credentials(): TuyaCredentials? = null
            override suspend fun profile(): DeviceProfile? = null
        }
        val failure = TuyaPlugGateway(client, empty, clock).readState() as GatewayResult.Failure
        assertEquals(FailureKind.NOT_CONFIGURED, failure.kind)
    }

    @Test fun `credenciais nunca aparecem em toString`() {
        val text = TuyaCredentials("meu-id", "SEGREDO-SUPER", "us", "dev").toString()
        assertTrue(!text.contains("SEGREDO-SUPER") && !text.contains("meu-id"))
    }
}
