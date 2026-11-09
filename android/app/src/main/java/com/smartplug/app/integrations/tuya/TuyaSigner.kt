package com.smartplug.app.integrations.tuya

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Assinatura da Tuya OpenAPI (HMAC-SHA256).
 *
 *   stringToSign = METHOD \n sha256(body) \n \n path?query
 *   sign = HMAC(secret, client_id + [access_token] + t + nonce + stringToSign).upper
 *
 * O access_token entra no material em chamadas autenticadas; no `/v1.0/token` não existe.
 */
object TuyaSigner {
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    fun stringToSign(method: String, body: String, pathWithQuery: String): String =
        "$method\n${sha256Hex(body)}\n\n$pathWithQuery"

    fun sign(
        clientId: String,
        secret: String,
        accessToken: String?,
        t: String,
        nonce: String,
        stringToSign: String,
    ): String {
        val material = clientId + (accessToken ?: "") + t + nonce + stringToSign
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(material.toByteArray(Charsets.UTF_8)).toHex().uppercase()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
