package com.smartplug.app.integrations.tuya

import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.FailureKind
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class TuyaException(val kind: FailureKind, val code: Int?, message: String) : Exception(message)

data class TuyaResponse(val result: JsonElement?, val latencyMs: Long)

/**
 * Cliente mínimo da Tuya OpenAPI. Apenas endpoints documentados são usados:
 *  - GET  /v1.0/token?grant_type=1
 *  - GET  /v1.0/devices/{id}                 (online + status)
 *  - GET  /v1.1/devices/{id}/specifications  (DPs, escalas, unidades)
 *  - POST /v1.0/devices/{id}/commands
 *
 * Nada de token, secret ou corpo de resposta é registrado em log.
 */
@Singleton
class TuyaApiClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val clock: Clock,
) {
    private class CachedToken(val value: String, val expiresAtMs: Long, val key: String)

    /** Apenas para testes: aponta para um servidor falso. */
    @Volatile internal var baseUrlOverride: String? = null

    private val tokenMutex = Mutex()
    private var cached: CachedToken? = null

    suspend fun get(creds: TuyaCredentials, path: String): TuyaResponse = call(creds, "GET", path, null)

    suspend fun post(creds: TuyaCredentials, path: String, body: String): TuyaResponse =
        call(creds, "POST", path, body)

    private suspend fun call(creds: TuyaCredentials, method: String, path: String, body: String?): TuyaResponse {
        var token = token(creds, forceRefresh = false)
        try {
            return execute(creds, method, path, body, token)
        } catch (e: TuyaException) {
            // 1010 = token expirado, 1011 = token inválido: renova uma vez.
            if (e.code == 1010 || e.code == 1011) {
                token = token(creds, forceRefresh = true)
                return execute(creds, method, path, body, token)
            }
            throw e
        }
    }

    private suspend fun token(creds: TuyaCredentials, forceRefresh: Boolean): String = tokenMutex.withLock {
        val key = "${creds.clientId}|${creds.region}"
        val current = cached
        if (!forceRefresh && current != null && current.key == key &&
            clock.nowMillis() < current.expiresAtMs - 60_000
        ) {
            return@withLock current.value
        }
        val response = execute(creds, "GET", "/v1.0/token?grant_type=1", null, accessToken = null)
        val result = response.result?.jsonObject
            ?: throw TuyaException(FailureKind.AUTH, null, "Resposta de token vazia.")
        val value = result["access_token"]?.primitiveContent()
            ?: throw TuyaException(FailureKind.AUTH, null, "Token não retornado.")
        val ttlSeconds = result["expire_time"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 7200
        cached = CachedToken(value, clock.nowMillis() + ttlSeconds * 1000L, key)
        value
    }

    private suspend fun execute(
        creds: TuyaCredentials,
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
    ): TuyaResponse {
        val region = TuyaRegion.fromCode(creds.region)
        val payload = body.orEmpty()
        val t = clock.nowMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val sign = TuyaSigner.sign(
            clientId = creds.clientId,
            secret = creds.clientSecret,
            accessToken = accessToken,
            t = t,
            nonce = nonce,
            stringToSign = TuyaSigner.stringToSign(method, payload, path),
        )
        val builder = Request.Builder()
            .url((baseUrlOverride ?: region.baseUrl) + path)
            .header("client_id", creds.clientId)
            .header("sign", sign)
            .header("t", t)
            .header("sign_method", "HMAC-SHA256")
            .header("nonce", nonce)
        if (accessToken != null) builder.header("access_token", accessToken)
        if (method == "POST") {
            builder.post(payload.toRequestBody("application/json".toMediaType()))
        } else {
            builder.get()
        }

        val started = System.nanoTime()
        val text = try {
            http.newCall(builder.build()).await().use { response ->
                response.body.string()
            }
        } catch (e: InterruptedIOException) {
            throw TuyaException(FailureKind.TIMEOUT, null, "Tempo esgotado ao falar com a Tuya.")
        } catch (e: IOException) {
            throw TuyaException(FailureKind.NO_NETWORK, null, "Sem conexão com a internet.")
        }
        val latency = (System.nanoTime() - started) / 1_000_000

        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw TuyaException(FailureKind.UNKNOWN, null, "Resposta inválida da Tuya.")
        }
        val success = (root["success"] as? JsonPrimitive)?.contentOrNull == "true"
        if (!success) throw root.toException()
        return TuyaResponse(root["result"], latency)
    }

    private fun JsonObject.toException(): TuyaException {
        val code = (this["code"] as? JsonPrimitive)?.intOrNull
        val msg = (this["msg"] as? JsonPrimitive)?.contentOrNull ?: "erro desconhecido"
        val (kind, hint) = when (code) {
            1004, 2009 -> FailureKind.AUTH to "Client ID/Secret inválidos."
            1010, 1011 -> FailureKind.AUTH to "Token expirado."
            1106 -> FailureKind.REJECTED to "O aparelho não está vinculado a este projeto Tuya."
            28841107 -> FailureKind.REJECTED to "Data center incorreto para este projeto."
            2007 -> FailureKind.REJECTED to "Acesso entre regiões não permitido."
            else -> FailureKind.REJECTED to ""
        }
        return TuyaException(kind, code, "$msg [código $code] $hint".trim())
    }

    private fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
