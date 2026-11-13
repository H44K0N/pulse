package dev.tischbein.pulse.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Compares the SHAPE of a produced JSON document with the contract example: every key of the
 * example must be present with the same JSON type. Returns a list of problems (empty = OK).
 *
 * - A null in the example means "nullable": the value may be null or any primitive.
 * - An array element must match at least one of the example's elements.
 * - Objects listed in [freeForm] (e.g. "settings") only need to be objects.
 */
fun shapeProblems(
    expected: JsonElement,
    actual: JsonElement,
    path: String = "$",
    freeForm: Set<String> = setOf("$.settings"),
): List<String> {
    val problems = mutableListOf<String>()
    when (expected) {
        is JsonNull -> if (actual !is JsonPrimitive) problems += "$path: expected null or a primitive, got $actual"
        is JsonPrimitive ->
            if (actual !is JsonPrimitive || actual is JsonNull || kind(actual) != kind(expected)) {
                problems += "$path: expected a ${kind(expected)}, got $actual"
            }
        is JsonObject -> {
            if (actual !is JsonObject) return listOf("$path: expected an object, got $actual")
            if (path in freeForm) return emptyList()
            for ((key, value) in expected) {
                val got = actual[key]
                if (got == null) problems += "$path.$key: missing" else problems += shapeProblems(value, got, "$path.$key", freeForm)
            }
        }
        is JsonArray -> {
            if (actual !is JsonArray) return listOf("$path: expected an array, got $actual")
            actual.forEachIndexed { i, element ->
                val matchesOne = expected.isEmpty() || expected.any { shapeProblems(it, element, "$path[$i]", freeForm).isEmpty() }
                if (!matchesOne) problems += shapeProblems(expected.first(), element, "$path[$i]", freeForm)
            }
        }
    }
    return problems
}

private fun kind(p: JsonPrimitive): String =
    when {
        p.isString -> "string"
        p.booleanOrNull != null -> "boolean"
        p.doubleOrNull != null -> "number"
        else -> "unknown"
    }
