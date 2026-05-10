package com.clawgui.android.core.nano.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun anyToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJson(v) })
    is List<*> -> JsonArray(value.map { anyToJson(it) })
    else -> JsonPrimitive(value.toString())
}

internal fun jsonToAny(el: JsonElement): Any? = when (el) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        el.isString -> el.content
        else -> el.booleanOrNull ?: el.intOrNull ?: el.longOrNull ?: el.doubleOrNull ?: el.content
    }
    is JsonArray -> el.map { jsonToAny(it) }
    is JsonObject -> el.mapValues { jsonToAny(it.value) }
}
