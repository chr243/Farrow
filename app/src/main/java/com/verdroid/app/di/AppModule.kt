package com.verdroid.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.verdroid.app.BuildConfig
import com.verdroid.app.agent.AgentRunner
import com.verdroid.app.agent.tools.*
import com.verdroid.app.data.git.GitManager
import com.verdroid.app.data.local.*
import com.verdroid.app.shizuku.ShellExecutor
import com.verdroid.app.data.network.*
import com.verdroid.app.data.repository.*
import com.verdroid.app.data.secure.SecureApiKeyRepository
import com.verdroid.app.data.settings.SettingsDataStoreRepository
import com.verdroid.app.data.settings.settingsDataStore
import com.verdroid.app.domain.repository.*
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
    fun provideDatabase(@ApplicationContext context: Context): VerdroidDatabase =
        Room.databaseBuilder(context, VerdroidDatabase::class.java, "verdroid.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun taskDao(db: VerdroidDatabase) = db.taskDao()
    @Provides fun messageDao(db: VerdroidDatabase) = db.messageDao()
    @Provides fun toolCallDao(db: VerdroidDatabase) = db.toolCallDao()
    @Provides fun rateLimitEventDao(db: VerdroidDatabase) = db.rateLimitEventDao()
    @Provides fun notificationDao(db: VerdroidDatabase) = db.notificationDao()
    @Provides fun memoryDao(db: VerdroidDatabase) = db.memoryDao()

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
    fun provideKiloApi(client: OkHttpClient, json: Json): com.verdroid.app.data.network.KiloApi = Retrofit.Builder()
        .baseUrl(com.verdroid.app.data.network.KiloApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(com.verdroid.app.data.network.KiloApi::class.java)

    @Provides @Singleton
    fun provideKiloProvider(api: com.verdroid.app.data.network.KiloApi, usage: com.verdroid.app.data.network.KiloUsage) =
        com.verdroid.app.data.network.KiloProvider(api, usage.counter, onRequest = { usage.record() })

    @Provides @Singleton
    fun provideSandbox(@ApplicationContext context: Context) = WorkspaceSandbox(File(context.filesDir, "workspace"))

    /** Shared user-visible folder /storage/emulated/0/Documents/Verdroid (Input/, Output/); needs All files access. */
    @Provides @Singleton
    fun provideSharedFolder(): com.verdroid.app.data.storage.SharedFolder = com.verdroid.app.data.storage.SharedFolder.android()

    /** Shizuku rish: the user-picked rish + rish_shizuku.dex copied into files/rish. */
    @Provides @Singleton
    fun provideRishStore(@ApplicationContext context: Context) = com.verdroid.app.shizuku.RishStore(File(context.filesDir, "rish"))

    @Provides @Singleton
    fun provideRishRunner(store: com.verdroid.app.shizuku.RishStore, shell: ShellExecutor) =
        com.verdroid.app.shizuku.RishRunner(store) { cmd, ms -> shell.exec(cmd, null, ms) }

    /** Agent-writable skills: files/skills/<id>/SKILL.md (app-internal, not Documents). */
    @Provides @Singleton
    fun provideSkillStore(@ApplicationContext context: Context) = com.verdroid.app.data.skills.SkillStore(File(context.filesDir, "skills"))

    @Provides @Singleton
    fun provideToolRegistry(
        skills: com.verdroid.app.data.skills.SkillStore,
        rishStore: com.verdroid.app.shizuku.RishStore,
        rishRunner: com.verdroid.app.shizuku.RishRunner,
        sandbox: WorkspaceSandbox,
        sharedFolder: com.verdroid.app.data.storage.SharedFolder,
        shell: ShellExecutor,
        git: GitManager,
        toolPrefs: com.verdroid.app.data.tools.ToolPrefs,
        mcp: com.verdroid.app.data.mcp.McpManager,
        memory: com.verdroid.app.data.memory.MemoryRepository,
        @ApplicationContext context: Context,
        cryptoClient: com.verdroid.app.data.crypto.CoinbaseExchangeClient,
        cryptoCreds: com.verdroid.app.data.crypto.CryptoCredentials,
        termux: com.verdroid.app.data.termux.TermuxManager,
    ): ToolRegistry = ToolRegistry(
        listOf(
            ReadFileTool(sandbox), WriteFileTool(sandbox), ListDirTool(sandbox),
            // Shared Documents/Verdroid folder (Input/, Output/) the user sees in their file manager
            WorkspaceListTool(sharedFolder), WorkspaceReadTool(sharedFolder),
            WorkspaceWriteTool(sharedFolder), WorkspaceDeleteTool(sharedFolder),
            // Default web search (keyless multi-engine, port of hec-ovi/websearch-skill) + fetch a known URL (raw or Markdown)
            WebSearchTool(), WebFetchTool(),
        ) +
            // Shizuku shell, Termux (RUN_COMMAND), JGit, Accessibility
            listOf(
                RunShellTool(shell, sandbox), TermuxRunTool(termux), RishRunTool(rishStore, rishRunner, shell),
                // Headless Chromium + Selenium inside Termux, and the agent's own Python scrapers (scripts stay in filesDir/workspace)
                SeleniumOpenTool(termux, sharedFolder), SeleniumPageSourceTool(termux, sharedFolder),
                SeleniumScreenshotTool(termux, sharedFolder), TermuxPythonTool(termux, sandbox),
                EbookTranslateTool(termux, sharedFolder),
                // PDF read/edit in Termux Python (PyMuPDF, pypdf fallback; installed on first use after the user agrees)
                PdfInfoTool(termux, sharedFolder), PdfExtractTextTool(termux, sharedFolder),
                PdfExtractPagesTool(termux, sharedFolder), PdfMergeTool(termux, sharedFolder), PdfAnnotateTool(termux, sharedFolder),
                GitStatusTool(git), GitCommitTool(git), GitCloneTool(git), GitPushTool(git),
                ScreenReadTool(), ScreenTapTool(), ScreenSwipeTool(), ScreenTypeTool(), ScreenGlobalActionTool(),
                // v0.9.16: persistent memory
                MemorySaveTool(memory), MemorySearchTool(memory), MemoryDeleteTool(memory),
                // Agent-writable skills (reusable procedures; enabled ones are injected into the system prompt)
                SkillListTool(skills), SkillGetTool(skills), SkillSaveTool(skills), SkillEditTool(skills), SkillDeleteTool(skills),
                // v1.0.12: native charts in the chat (+ PNG export for sharing)
                ChartTool(File(context.filesDir, "charts"), com.verdroid.app.ui.chart.AndroidChartPng()),
            ) +
            // Crypto: Coinbase Exchange market data + optional live trading (place/cancel off by default)
            com.verdroid.app.agent.tools.CryptoToolFactory(cryptoClient, cryptoCreds).tools() +
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
