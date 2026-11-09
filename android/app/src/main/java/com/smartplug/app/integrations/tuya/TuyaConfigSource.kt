package com.smartplug.app.integrations.tuya

/** Fonte das credenciais e do perfil do dispositivo (implementada por AppPrefs; trocável em testes). */
interface TuyaConfigSource {
    suspend fun credentials(): TuyaCredentials?
    suspend fun profile(): DeviceProfile?
}
