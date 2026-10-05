package com.farrow.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.farrow.app.BuildConfig
import com.farrow.app.agent.AgentRunner
import com.farrow.app.agent.tools.*
import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.FallbackBrowser
import com.farrow.app.data.git.GitManager
import com.farrow.app.data.local.*
import com.farrow.app.shizuku.ShellExecutor
import com.farrow.app.data.social.SelectorStore
import com.farrow.app.data.social.SessionGuard
import com.farrow.app.data.network.*
import com.farrow.app.data.repository.*
import com.farrow.app.data.secure.SecureApiKeyRepository
import com.farrow.app.data.settings.SettingsDataStoreRepository
import com.farrow.app.data.settings.settingsDataStore
import com.farrow.app.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FarrowDatabase =
        Room.databaseBuilder(context, FarrowDatabase::class.java, "farrow.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun taskDao(db: FarrowDatabase) = db.taskDao()
    @Provides fun messageDao(db: FarrowDatabase) = db.messageDao()
    @Provides fun toolCallDao(db: FarrowDatabase) = db.toolCallDao()
    @Provides fun rateLimitEventDao(db: FarrowDatabase) = db.rateLimitEventDao()
    @Provides fun notificationDao(db: FarrowDatabase) = db.notificationDao()
    @Provides fun memoryDao(db: FarrowDatabase) = db.memoryDao()

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Provides @Singleton
    fun provideOkHttp(store: RateLimitHeaderStore): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(OpenRouterHeadersInterceptor())
        .addInterceptor(RateLimitInterceptor(store))
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
            })
        }
        .build()

    @Provides @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(OpenRouterApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun provideOpenRouterApi(retrofit: Retrofit): OpenRouterApi = retrofit.create(OpenRouterApi::class.java)

    /** Kilo Gateway: same client (timeouts, tag stripping), different base URL, no Authorization header. */
    @Provides @Singleton
    fun provideKiloApi(client: OkHttpClient, json: Json): com.farrow.app.data.network.KiloApi = Retrofit.Builder()
        .baseUrl(com.farrow.app.data.network.KiloApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(com.farrow.app.data.network.KiloApi::class.java)

    @Provides @Singleton
    fun provideKiloProvider(api: com.farrow.app.data.network.KiloApi, usage: com.farrow.app.data.network.KiloUsage) =
        com.farrow.app.data.network.KiloProvider(api, usage.counter, onRequest = { usage.record() })

    @Provides @Singleton
    fun provideSandbox(@ApplicationContext context: Context) = WorkspaceSandbox(File(context.filesDir, "workspace"))

    @Provides @Singleton
    fun provideToolRegistry(
        sandbox: WorkspaceSandbox,
        bridge: BridgeClient,
        fallbackBrowser: FallbackBrowser,
        selectors: SelectorStore,
        sessionGuard: SessionGuard,
        shell: ShellExecutor,
        git: GitManager,
        toolPrefs: com.farrow.app.data.tools.ToolPrefs,
        mcp: com.farrow.app.data.mcp.McpManager,
        memory: com.farrow.app.data.memory.MemoryRepository,
        @ApplicationContext context: Context,
        client: com.farrow.app.data.network.OpenRouterClient,
        settings: com.farrow.app.domain.repository.SettingsRepository,
        vision: com.farrow.app.data.network.ModelCapabilities,
        appPrefs: com.farrow.app.data.prefs.AppPrefs,
        browserOps: com.farrow.app.data.browser.BrowserOpsManager,
        cryptoClient: com.farrow.app.data.crypto.CoinbaseExchangeClient,
        cryptoCreds: com.farrow.app.data.crypto.CryptoCredentials,
    ): ToolRegistry = ToolRegistry(
        listOf(
            ReadFileTool(sandbox), WriteFileTool(sandbox), ListDirTool(sandbox),
            // Phase 4: internal browser (Termux Browser Pilot bridge, HTTP+Jsoup fallback)
            WebScrapeTool(bridge, fallbackBrowser, { url -> com.farrow.app.agent.tools.SiteScopes.textScopeFor(url, selectors) }) { !appPrefs.loadImages.value },
            WebFetchTool(),
            WebClickTool(bridge) { !appPrefs.loadImages.value }, WebTypeTool(bridge) { !appPrefs.loadImages.value }, WebSessionTool(bridge),
            // reset_browser: Settings > Internal browser setup > Reset browser, from the agent (background op, awaited)
            com.farrow.app.agent.tools.ResetBrowserTool(browserOps::resetBrowser, browserOps.state),
            WebScreenshotTool(bridge, File(context.filesDir, "screenshots"), com.farrow.app.data.browser.AndroidImageEncoder(),
                activeModelSeesImages = {
                    val m = client.lastModel.value ?: settings.currentModels().firstOrNull()
                    m != null && vision.supportsImages(m)
                }),
        ) +
            // Phase 5: X.com (x_status, x_post, x_scrape)
            SocialToolFactory("x", SelectorStore.X, selectors, bridge, sessionGuard).tools(postMaxChars = 280) +
            // x_post_beta (off by default, Tools page "Beta: faster X posting"): x_post 1:1 with instant typing
            listOf(com.farrow.app.agent.tools.XPostBetaTool(selectors, bridge, sessionGuard)) +
            // Phase 9: Facebook (fb_status, fb_post, fb_scrape) — same engine, lower priority/minimal
            SocialToolFactory("fb", SelectorStore.FACEBOOK, selectors, bridge, sessionGuard).tools(postMaxChars = 5_000) +
            // Phase 6: Shizuku shell, JGit, Accessibility
            listOf(
                RunShellTool(shell, sandbox), TermuxRunTool(bridge),
                GitStatusTool(git), GitCommitTool(git), GitCloneTool(git), GitPushTool(git),
                ScreenReadTool(), ScreenTapTool(), ScreenSwipeTool(), ScreenTypeTool(), ScreenGlobalActionTool(),
                // v0.9.16: persistent memory
                MemorySaveTool(memory), MemorySearchTool(memory), MemoryDeleteTool(memory),
                // v1.0.12: native charts in the chat (+ PNG export for sharing)
                ChartTool(File(context.filesDir, "charts"), com.farrow.app.ui.chart.AndroidChartPng()),
            ) +
            // Crypto: Coinbase Exchange market data + optional live trading (place/cancel off by default)
            com.farrow.app.agent.tools.CryptoToolFactory(cryptoClient, cryptoCreds).tools() +
            StubTool.all(),
        toolPrefs,
        dynamic = mcp::agentTools,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun taskRepo(impl: TaskRepositoryImpl): TaskRepository
    @Binds abstract fun keyRepo(impl: SecureApiKeyRepository): ApiKeyRepository
    @Binds abstract fun settingsRepo(impl: SettingsDataStoreRepository): SettingsRepository
    @Binds abstract fun rateLimitRepo(impl: RateLimitRepositoryImpl): RateLimitRepository
    @Binds abstract fun notificationRepo(impl: NotificationRepositoryImpl): NotificationRepository
    @Binds abstract fun quotaRepo(impl: QuotaRepositoryImpl): QuotaRepository
    @Binds abstract fun agentController(impl: AgentRunner): AgentController
}
