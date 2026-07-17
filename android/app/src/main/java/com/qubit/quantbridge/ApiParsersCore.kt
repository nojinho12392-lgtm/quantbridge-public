package com.qubit.quantbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun parseStringArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotBlank()) add(value)
        }
    }
}

internal fun firstCleanString(o: JSONObject, vararg keys: String): String? {
    for (key in keys) {
        o.cleanString(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

internal fun impactFallbackLabel(label: String?): String {
    return when (label?.lowercase(Locale.US)) {
        "positive" -> "긍정"
        "negative" -> "부정"
        else -> "중립"
    }
}

internal fun List<Double>.averageOrNull(): Double? {
    val clean = filter(Double::isFinite)
    if (clean.isEmpty()) return null
    return clean.average()
}

fun jsonToMap(json: JSONObject): Map<String, String> {
    val map = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = json.cleanString(key).orEmpty()
    }
    return map
}

fun JSONObject.cleanString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)?.toString()?.trim() ?: return null
    return value.takeUnless { it.isBlank() || it.equals("nan", true) || it.equals("null", true) }
}

fun JSONObject.cleanStringList(vararg keys: String): List<String> {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        val raw = opt(key)
        val values = when (raw) {
            is JSONArray -> buildList {
                for (i in 0 until raw.length()) {
                    raw.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            else -> raw?.toString()
                ?.split(",", "|")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }
        if (values.isNotEmpty()) return values.distinct()
    }
    return emptyList()
}

fun JSONObject.cleanDouble(key: String): Double? {
    val raw = cleanString(key) ?: return null
    val clean = raw
        .replace(",", "")
        .replace("$", "")
        .replace("₩", "")
        .replace("원", "")
        .trim()
    val value = clean.removeSuffix("%").toDoubleOrNull() ?: return null
    if (value.isNaN() || value.isInfinite()) return null
    if (clean.endsWith("%")) return value / 100.0
    return value
}

fun JSONObject.cleanFirstDouble(vararg keys: String): Double? {
    for (key in keys) {
        cleanDouble(key)?.let { return it }
    }
    return null
}

fun JSONObject.cleanInt(key: String): Int? = cleanDouble(key)?.toInt()

fun JSONObject.cleanFirstInt(vararg keys: String): Int? {
    for (key in keys) {
        cleanInt(key)?.let { return it }
    }
    return null
}

fun JSONObject.cleanBool(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        else -> (raw?.toString() ?: return null).trim().lowercase(Locale.US).let {
            when (it) {
                "true", "1", "yes", "y" -> true
                "false", "0", "no", "n" -> false
                else -> null
            }
        }
    }
}
