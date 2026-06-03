package com.example.calibredrive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class IndexParser {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    fun parse(raw: String): LibraryIndex = json.decodeFromString(LibraryIndex.serializer(), raw)
}
