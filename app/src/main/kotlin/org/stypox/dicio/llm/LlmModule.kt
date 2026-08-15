package org.stypox.dicio.llm

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import androidx.datastore.core.DataStore
import okhttp3.OkHttpClient
import org.stypox.dicio.health.HealthDataStore
import org.stypox.dicio.llm.orchestrator.LlmOrchestrator
import org.stypox.dicio.llm.orchestrator.ToolRegistry
import org.stypox.dicio.llm.orchestrator.tools.CurrentTimeTool
import org.stypox.dicio.llm.orchestrator.tools.HealthQueryTool
import org.stypox.dicio.llm.orchestrator.tools.RememberTool
import org.stypox.dicio.llm.orchestrator.tools.WebSearchTool
import org.stypox.dicio.settings.datastore.UserSettings
import javax.inject.Singleton

/**
 * Hilt bindings for the on-device LLM feature.
 *
 * The engine, model manager, knowledge store and tool registry are all singletons so the model
 * stays loaded across the app and the [LlmService]. To expose more Dicio skills to the model, add
 * their [org.stypox.dicio.llm.orchestrator.LlmTool] adapters to [provideToolRegistry].
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideLlmEngine(): LlmEngine = LlamaCppEngine()

    @Provides
    @Singleton
    fun provideOllamaEngine(okHttpClient: OkHttpClient): OllamaEngine = OllamaEngine(okHttpClient)

    @Provides
    @Singleton
    fun provideKnowledgeStore(@ApplicationContext context: Context): KnowledgeStore =
        KnowledgeStore(context)

    @Provides
    @Singleton
    fun provideGgufModelManager(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        engine: LlmEngine,
    ): GgufModelManager = GgufModelManager(context, okHttpClient, engine)

    @Provides
    @Singleton
    fun provideToolRegistry(
        knowledgeStore: KnowledgeStore,
        healthDataStore: HealthDataStore,
    ): ToolRegistry = ToolRegistry(
        listOf(
            // memory / "mitlernen"
            RememberTool(knowledgeStore),
            // fitness/health data imported from Gadgetbridge/GPX
            HealthQueryTool(healthDataStore),
            // skill-backed tools (extend this list to expose more skills to the model)
            CurrentTimeTool(),
            WebSearchTool(),
        )
    )

    @Provides
    @Singleton
    fun provideLlmOrchestrator(
        localEngine: LlmEngine,
        serverEngine: OllamaEngine,
        modelManager: GgufModelManager,
        toolRegistry: ToolRegistry,
        knowledgeStore: KnowledgeStore,
        healthDataStore: HealthDataStore,
        dataStore: DataStore<UserSettings>,
    ): LlmOrchestrator = LlmOrchestrator(
        localEngine = localEngine,
        serverEngine = serverEngine,
        modelManager = modelManager,
        toolRegistry = toolRegistry,
        knowledgeStore = knowledgeStore,
        healthDataStore = healthDataStore,
        dataStore = dataStore,
    )
}
