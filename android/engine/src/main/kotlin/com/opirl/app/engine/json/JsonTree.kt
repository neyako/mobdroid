package com.opirl.app.engine.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val EMPTY_OBJECT = JsonObject(emptyMap())

fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()

fun JsonObject.single(): Pair<String, JsonObject>? {
    val entry = entries.singleOrNull() ?: return null
    return entry.key to (entry.value as? JsonObject ?: EMPTY_OBJECT)
}

fun case(name: String, fields: JsonObject = EMPTY_OBJECT) = JsonObject(mapOf(name to fields))

fun JsonObject.withoutNulls(): JsonObject = JsonObject(filterValues { it !is JsonNull })
