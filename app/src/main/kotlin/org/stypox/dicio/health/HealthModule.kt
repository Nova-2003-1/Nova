package org.stypox.dicio.health

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the offline fitness/health import feature.
 */
@Module
@InstallIn(SingletonComponent::class)
object HealthModule {

    @Provides
    @Singleton
    fun provideHealthDataStore(@ApplicationContext context: Context): HealthDataStore =
        HealthDataStore(context)

    @Provides
    @Singleton
    fun provideHealthImportManager(
        @ApplicationContext context: Context,
        store: HealthDataStore,
    ): HealthImportManager = HealthImportManager(context, store)
}
