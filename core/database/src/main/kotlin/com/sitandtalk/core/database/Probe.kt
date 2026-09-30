package com.sitandtalk.core.database

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

@Entity(tableName = "probe")
data class ProbeEntity(@PrimaryKey val id: String)

@Database(entities = [ProbeEntity::class], version = 1, exportSchema = false)
abstract class ProbeDatabase : RoomDatabase()
