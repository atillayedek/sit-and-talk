package com.sitandtalk.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Typed calls to Postgres RPCs and Edge Functions with consistent error mapping. */
@Singleton
class Api @Inject constructor(private val provider: SupabaseProvider) {

    val client: SupabaseClient get() = provider.client

    suspend fun rpcRaw(function: String, params: JsonObject = JsonObject(emptyMap())): String = apiCall {
        client.postgrest.rpc(function, params).data
    }

    suspend inline fun <reified T> rpc(function: String, noinline params: JsonObjectBuilder.() -> Unit = {}): T {
        val raw = rpcRaw(function, buildJsonObject(params))
        return apiCall { AppJson.decodeFromString<T>(raw.ifBlank { "null" }) }
    }

    suspend fun rpcUnit(function: String, params: JsonObjectBuilder.() -> Unit = {}) {
        rpcRaw(function, buildJsonObject(params))
    }

    suspend fun functionRaw(name: String, body: JsonObject): String = apiCall {
        client.functions.invoke(name) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyAsText()
    }

    suspend inline fun <reified T> function(name: String, noinline body: JsonObjectBuilder.() -> Unit): T {
        val raw = functionRaw(name, buildJsonObject(body))
        return apiCall { AppJson.decodeFromString<T>(raw) }
    }
}

fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

fun JsonObjectBuilder.putJson(key: String, value: JsonElement) {
    put(key, value)
}
