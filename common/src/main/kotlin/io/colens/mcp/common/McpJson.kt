package io.colens.mcp.common

import com.inductiveautomation.ignition.common.gson.Gson
import com.inductiveautomation.ignition.common.gson.GsonBuilder
import com.inductiveautomation.ignition.common.gson.JsonArray
import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonNull
import com.inductiveautomation.ignition.common.gson.JsonObject
import com.inductiveautomation.ignition.common.gson.JsonParser
import com.inductiveautomation.ignition.common.gson.JsonPrimitive

/**
 * Thin helpers over the Gson that Ignition already ships (relocated to
 * `com.inductiveautomation.ignition.common.gson`, via the `ia-gson` artifact that `common`
 * depends on). Using it means this module bundles no JSON library of its own.
 */
object McpJson {
    val gson: Gson = GsonBuilder().serializeNulls().create()
    val prettyGson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun parse(text: String): JsonElement = JsonParser.parseString(text)

    fun toString(element: JsonElement): String = gson.toJson(element)

    fun toPrettyString(element: JsonElement): String = prettyGson.toJson(element)
}

// ---------------------------------------------------------------------------
// Building
// ---------------------------------------------------------------------------

fun jsonObject(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)

fun jsonArray(build: JsonArray.() -> Unit): JsonArray = JsonArray().apply(build)

fun jsonArrayOf(elements: Iterable<JsonElement>): JsonArray =
    JsonArray().apply { elements.forEach { add(it) } }

fun jsonArrayOfStrings(values: Iterable<String>): JsonArray =
    JsonArray().apply { values.forEach { add(it) } }

/** Null-safe put. Gson's own `addProperty` overloads are ambiguous from Kotlin for nulls. */
fun JsonObject.put(key: String, value: String?) {
    if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
}

fun JsonObject.put(key: String, value: Number?) {
    if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
}

fun JsonObject.put(key: String, value: Boolean?) {
    if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
}

fun JsonObject.put(key: String, value: JsonElement?) {
    add(key, value ?: JsonNull.INSTANCE)
}

/**
 * Best-effort conversion of an arbitrary JVM value (tag values, SQL column values, ...) into
 * JSON. Anything we don't have a primitive for is rendered via [toString] so a tool never
 * fails purely because a value had an exotic type.
 */
fun toJsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is CharSequence -> JsonPrimitive(value.toString())
    is Collection<*> -> JsonArray().apply { value.forEach { add(toJsonValue(it)) } }
    is Array<*> -> JsonArray().apply { value.forEach { add(toJsonValue(it)) } }
    is Map<*, *> -> jsonObject {
        value.forEach { (k, v) -> add(k.toString(), toJsonValue(v)) }
    }
    else -> JsonPrimitive(value.toString())
}

// ---------------------------------------------------------------------------
// Reading tool arguments
// ---------------------------------------------------------------------------

class McpArgumentException(message: String) : RuntimeException(message)

fun JsonObject.optString(key: String): String? {
    val e = get(key) ?: return null
    return if (e.isJsonNull) null else e.asString
}

fun JsonObject.requireString(key: String): String =
    optString(key) ?: throw McpArgumentException("Missing required argument '$key'")

fun JsonObject.optInt(key: String, default: Int): Int {
    val e = get(key) ?: return default
    return if (e.isJsonNull) default else e.asInt
}

fun JsonObject.optLong(key: String): Long? {
    val e = get(key) ?: return null
    return if (e.isJsonNull) null else e.asLong
}

fun JsonObject.optBoolean(key: String, default: Boolean): Boolean {
    val e = get(key) ?: return default
    return if (e.isJsonNull) default else e.asBoolean
}

fun JsonObject.optObject(key: String): JsonObject? {
    val e = get(key) ?: return null
    return if (e.isJsonObject) e.asJsonObject else null
}

fun JsonObject.optArray(key: String): JsonArray? {
    val e = get(key) ?: return null
    return if (e.isJsonArray) e.asJsonArray else null
}

/** Reads a string array argument, also accepting a bare string for convenience. */
fun JsonObject.stringList(key: String): List<String> {
    val e = get(key) ?: return emptyList()
    if (e.isJsonNull) return emptyList()
    if (e.isJsonPrimitive) return listOf(e.asString)
    if (!e.isJsonArray) throw McpArgumentException("Argument '$key' must be an array of strings")
    return e.asJsonArray.map { it.asString }
}

fun JsonObject.requireStringList(key: String): List<String> =
    stringList(key).ifEmpty { throw McpArgumentException("Missing required argument '$key'") }
