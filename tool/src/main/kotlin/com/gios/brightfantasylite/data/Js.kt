package com.gios.brightfantasylite.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The handful of `org.json` calls the original parser made, on top of
 * kotlinx.serialization's tree.
 *
 * BrightFantasy parsed with `org.json`, which ships inside Android but is only a stub on
 * the JVM that unit tests run on, and the SDK does not allow adding the real jar. These
 * two wrappers copy its `opt*` behavior (missing or null means the default, numbers
 * written as strings still read as numbers) so the parser could be ported line for line.
 */
class JObj(val obj: JsonObject) {

    fun keys(): Iterator<String> = obj.keys.iterator()

    fun has(key: String): Boolean = obj[key] != null && obj[key] !is JsonNull

    private fun prim(key: String): JsonPrimitive? =
        (obj[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    fun optJSONObject(key: String): JObj? = (obj[key] as? JsonObject)?.let(::JObj)

    fun optJSONArray(key: String): JArr? = (obj[key] as? JsonArray)?.let(::JArr)

    fun optString(key: String, default: String = ""): String = prim(key)?.content ?: default

    fun optInt(key: String, default: Int = 0): Int =
        prim(key)?.let { it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() } ?: default

    fun optLong(key: String, default: Long = 0L): Long =
        prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: default

    fun optDouble(key: String, default: Double = Double.NaN): Double =
        prim(key)?.doubleOrNull ?: default

    fun optBoolean(key: String, default: Boolean = false): Boolean =
        prim(key)?.booleanOrNull ?: default

    companion object {
        val EMPTY = JObj(JsonObject(emptyMap()))

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Throws on anything that is not a JSON object, as `JSONObject(String)` does. */
        fun parse(text: String): JObj {
            val element: JsonElement = json.parseToJsonElement(text)
            return JObj(element as? JsonObject ?: throw IllegalArgumentException("not an object"))
        }
    }
}

class JArr(val arr: JsonArray) {

    fun length(): Int = arr.size

    fun optJSONObject(i: Int): JObj? = (arr.getOrNull(i) as? JsonObject)?.let(::JObj)

    fun optInt(i: Int, default: Int = 0): Int {
        val p = (arr.getOrNull(i) as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return default
        return p.longOrNull?.toInt() ?: p.doubleOrNull?.toInt() ?: default
    }

    fun optString(i: Int, default: String = ""): String =
        (arr.getOrNull(i) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: default

    companion object {
        val EMPTY = JArr(JsonArray(emptyList()))
    }
}
