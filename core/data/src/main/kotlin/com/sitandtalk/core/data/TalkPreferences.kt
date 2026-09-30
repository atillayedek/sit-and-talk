package com.sitandtalk.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.sitandtalk.core.model.TalkIntent
import com.sitandtalk.core.model.TalkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Non-sensitive matching preferences remembered on the device. */
data class TalkPrefs(
    val mode: TalkMode = TalkMode.Voice,
    val intent: TalkIntent = TalkIntent.Chat,
    val alias: String = "",
    val languages: Set<String> = emptySet(),
    val strictLanguage: Boolean = false,
    val showIcebreakers: Boolean = true,
)

@Singleton
class TalkPreferences @Inject constructor(private val store: DataStore<Preferences>) {
    private val mode = stringPreferencesKey("talk_mode")
    private val intent = stringPreferencesKey("talk_intent")
    private val alias = stringPreferencesKey("talk_alias")
    private val languages = stringSetPreferencesKey("talk_languages")
    private val strict = booleanPreferencesKey("talk_strict_language")
    private val icebreakers = booleanPreferencesKey("talk_icebreakers")

    val prefs: Flow<TalkPrefs> = store.data.map { p ->
        TalkPrefs(
            mode = p[mode]?.let { v -> TalkMode.entries.firstOrNull { it.name == v } } ?: TalkMode.Voice,
            intent = p[intent]?.let { v -> TalkIntent.entries.firstOrNull { it.name == v } } ?: TalkIntent.Chat,
            alias = p[alias].orEmpty(),
            languages = p[languages].orEmpty(),
            strictLanguage = p[strict] ?: false,
            showIcebreakers = p[icebreakers] ?: true,
        )
    }

    suspend fun update(transform: (TalkPrefs) -> TalkPrefs, current: TalkPrefs) {
        val next = transform(current)
        store.edit { p ->
            p[mode] = next.mode.name
            p[intent] = next.intent.name
            p[alias] = next.alias
            p[languages] = next.languages
            p[strict] = next.strictLanguage
            p[icebreakers] = next.showIcebreakers
        }
    }
}
