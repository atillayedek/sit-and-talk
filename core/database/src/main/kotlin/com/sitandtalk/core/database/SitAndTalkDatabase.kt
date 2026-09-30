package com.sitandtalk.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [ConversationCacheEntity::class, MessageCacheEntity::class, OutboxMessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SitAndTalkDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationCacheDao
    abstract fun messages(): MessageCacheDao
    abstract fun outbox(): OutboxDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SitAndTalkDatabase =
        Room.databaseBuilder(context, SitAndTalkDatabase::class.java, "sitandtalk.db")
            // Cache only: a schema change may safely drop it; the server is the source of truth.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun conversations(db: SitAndTalkDatabase) = db.conversations()
    @Provides fun messages(db: SitAndTalkDatabase) = db.messages()
    @Provides fun outbox(db: SitAndTalkDatabase) = db.outbox()
}
