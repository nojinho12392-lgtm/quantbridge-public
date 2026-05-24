package com.example.myapplication

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private val emptyJsonTokens = setOf("", "null", "none", "nan", "na", "n/a")

internal fun JsonObject.qbString(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        this[key].qbStringOrNull()?.takeUnless { it.lowercase() in emptyJsonTokens }
    }
}

internal fun JsonObject.qbDouble(vararg keys: String): Double? {
    return keys.firstNotNullOfOrNull { key ->
        this[key].qbDoubleOrNull()?.takeIf(Double::isFinite)
    }
}

internal fun JsonObject.qbInt(vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key ->
        this[key].qbIntOrNull()
    }
}

internal fun JsonObject.qbBool(vararg keys: String): Boolean? {
    return keys.firstNotNullOfOrNull { key ->
        this[key].qbBoolOrNull()
    }
}

internal fun JsonObject.qbObject(vararg keys: String): JsonObject? {
    return keys.firstNotNullOfOrNull { key ->
        this[key] as? JsonObject
    }
}

internal fun JsonObject.qbObjects(vararg keys: String): List<JsonObject> {
    val element = keys.firstNotNullOfOrNull { key -> this[key] } ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }
}

private fun JsonElement?.qbStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()
}

private fun JsonElement?.qbDoubleOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
}

private fun JsonElement?.qbIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.doubleOrNull?.toInt() ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
}

private fun JsonElement?.qbBoolOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> null
    }
}
