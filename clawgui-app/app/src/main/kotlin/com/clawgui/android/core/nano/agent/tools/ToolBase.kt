package com.clawgui.android.core.nano.agent.tools

abstract class Tool {

    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, Any?>

    abstract suspend fun execute(params: Map<String, Any?>): Any?

    fun castParams(params: Map<String, Any?>): Map<String, Any?> {
        val schema = parameters
        if (schema["type"] != "object") return params
        return castObject(params, schema)
    }

    private fun castObject(obj: Any?, schema: Map<String, Any?>): Map<String, Any?> {
        if (obj !is Map<*, *>) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val objMap = obj as Map<String, Any?>
        val props = schema["properties"]
        if (props !is Map<*, *>) return objMap
        @Suppress("UNCHECKED_CAST")
        val propsMap = props as Map<String, Any?>
        return objMap.mapValues { (k, v) ->
            val propSchema = propsMap[k]
            if (propSchema is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                castValue(v, propSchema as Map<String, Any?>)
            } else v
        }
    }

    private fun castValue(value: Any?, schema: Map<String, Any?>): Any? {
        val targetType = resolveType(schema["type"]) ?: return value
        return when (targetType) {
            "integer" -> when (value) {
                is Int -> value
                is Long -> value.toInt()
                is String -> value.toIntOrNull() ?: value
                else -> value
            }
            "number" -> when (value) {
                is Int, is Long, is Float, is Double -> value
                is String -> value.toDoubleOrNull() ?: value
                else -> value
            }
            "string" -> if (value == null) null else value.toString()
            "boolean" -> when (value) {
                is Boolean -> value
                is String -> when (value.lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> value
                }
                else -> value
            }
            "array" -> {
                if (value !is List<*>) return value
                val itemSchema = schema["items"]
                if (itemSchema is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    value.map { castValue(it, itemSchema as Map<String, Any?>) }
                } else value
            }
            "object" -> castObject(value, schema)
            else -> value
        }
    }

    fun validateParams(params: Map<String, Any?>): List<String> {
        val schema = parameters
        return validate(params, schema + mapOf("type" to "object"), "")
    }

    private fun validate(value: Any?, schema: Map<String, Any?>, path: String): List<String> {
        val rawType = schema["type"]
        val nullable = (rawType is List<*> && "null" in rawType) ||
            schema["nullable"] == true
        val t = resolveType(rawType)
        val label = path.ifEmpty { "parameter" }

        if (nullable && value == null) return emptyList()

        val typeErrors = when (t) {
            "integer" -> if (value !is Int && value !is Long) listOf("$label should be integer") else emptyList()
            "number" -> if (value !is Number || value is Boolean) listOf("$label should be number") else emptyList()
            "string" -> if (value !is String) listOf("$label should be string") else emptyList()
            "boolean" -> if (value !is Boolean) listOf("$label should be boolean") else emptyList()
            "array" -> if (value !is List<*>) listOf("$label should be array") else emptyList()
            "object" -> if (value !is Map<*, *>) listOf("$label should be object") else emptyList()
            else -> emptyList()
        }
        if (typeErrors.isNotEmpty()) return typeErrors

        val errors = mutableListOf<String>()

        val enum = schema["enum"]
        if (enum is List<*> && value !in enum) {
            errors += "$label must be one of $enum"
        }

        if (t in listOf("integer", "number") && value is Number) {
            val d = value.toDouble()
            val min = (schema["minimum"] as? Number)?.toDouble()
            val max = (schema["maximum"] as? Number)?.toDouble()
            if (min != null && d < min) errors += "$label must be >= $min"
            if (max != null && d > max) errors += "$label must be <= $max"
        }

        if (t == "string" && value is String) {
            val minLen = (schema["minLength"] as? Number)?.toInt()
            val maxLen = (schema["maxLength"] as? Number)?.toInt()
            if (minLen != null && value.length < minLen) errors += "$label must be at least $minLen chars"
            if (maxLen != null && value.length > maxLen) errors += "$label must be at most $maxLen chars"
        }

        if (t == "object" && value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val objMap = value as Map<String, Any?>
            val props = schema["properties"]
            val required = schema["required"]
            if (required is List<*>) {
                for (k in required) {
                    if (k is String && k !in objMap) {
                        errors += "missing required ${if (path.isNotEmpty()) "$path.$k" else k}"
                    }
                }
            }
            if (props is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val propsMap = props as Map<String, Any?>
                for ((k, v) in objMap) {
                    val propSchema = propsMap[k]
                    if (propSchema is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        errors += validate(v, propSchema as Map<String, Any?>, if (path.isNotEmpty()) "$path.$k" else k)
                    }
                }
            }
        }

        if (t == "array" && value is List<*>) {
            val itemSchema = schema["items"]
            if (itemSchema is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                for ((i, item) in value.withIndex()) {
                    errors += validate(item, itemSchema as Map<String, Any?>, if (path.isNotEmpty()) "$path[$i]" else "[$i]")
                }
            }
        }

        return errors
    }

    fun toSchema(): Map<String, Any?> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parameters,
        ),
    )

    companion object {
        fun resolveType(t: Any?): String? = when (t) {
            is List<*> -> t.firstOrNull { it != "null" } as? String
            is String -> t
            else -> null
        }
    }
}
