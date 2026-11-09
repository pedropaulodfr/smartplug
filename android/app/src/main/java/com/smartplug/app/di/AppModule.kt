package com.smartplug.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.smartplug.app.core.SystemClock
import com.smartplug.app.data.db.AppDatabase
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.data.repo.AutomationRepository
import com.smartplug.app.data.repo.ExecutionListener
import com.smartplug.app.data.repo.ExecutionRepository
import com.smartplug.app.data.repo.LocationRepository
import com.smartplug.app.domain.engine.ActionExecutor
import com.smartplug.app.domain.engine.ConditionEvaluator
import com.smartplug.app.domain.engine.ElectricEvaluator
import com.smartplug.app.domain.engine.LocationEvaluator
import com.smartplug.app.domain.engine.PlugPowerExecutor
import com.smartplug.app.domain.engine.PlugStateEvaluator
import com.smartplug.app.domain.engine.TimeEvaluator
import com.smartplug.app.domain.gateway.AutomationStore
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.ExecutionLog
import com.smartplug.app.domain.gateway.LocationStateSource
import com.smartplug.app.domain.gateway.PlugGateway
import com.smartplug.app.domain.gateway.PlugMemory
import com.smartplug.app.integrations.tuya.TuyaConfigSource
import com.smartplug.app.integrations.tuya.TuyaPlugGateway
import com.smartplug.app.notifications.AutomationNotifier
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "smartplug_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "smartplug.db").build()

    @Provides @Singleton
    fun clock(): Clock = SystemClock

    @Provides
    fun executionListener(notifier: AutomationNotifier): ExecutionListener = notifier

    // ---- extensibilidade do motor: registre aqui novos avaliadores e executores
    @Provides @IntoSet fun timeEvaluator(): ConditionEvaluator = TimeEvaluator()
    @Provides @IntoSet fun locationEvaluator(): ConditionEvaluator = LocationEvaluator()
    @Provides @IntoSet fun electricEvaluator(): ConditionEvaluator = ElectricEvaluator()
    @Provides @IntoSet fun plugStateEvaluator(): ConditionEvaluator = PlugStateEvaluator()
    @Provides @IntoSet fun powerExecutor(): ActionExecutor = PlugPowerExecutor()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun automationStore(impl: AutomationRepository): AutomationStore
    @Binds abstract fun executionLog(impl: ExecutionRepository): ExecutionLog
    @Binds abstract fun locationSource(impl: LocationRepository): LocationStateSource
    @Binds abstract fun plugMemory(impl: AppPrefs): PlugMemory
    @Binds abstract fun plugGateway(impl: TuyaPlugGateway): PlugGateway
    @Binds abstract fun tuyaConfig(impl: AppPrefs): TuyaConfigSource
}
