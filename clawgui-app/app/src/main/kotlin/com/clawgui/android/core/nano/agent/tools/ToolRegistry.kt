package com.clawgui.android.core.nano.agent.tools

class ToolRegistry {

    private val tools: MutableMap<String, Tool> = mutableMapOf()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): Tool? = tools[name]

    fun has(name: String): Boolean = name in tools

    fun getDefinitions(): List<Map<String, Any?>> = tools.values.map { it.toSchema() }

    suspend fun execute(name: String, params: Map<String, Any?>): Any? {
        val hint = "\n\n[Analyze the error above and try a different approach.]"
        val tool = tools[name]
            ?: return "Error: Tool '$name' not found. Available: ${toolNames.joinToString()}"

        return try {
            val castParams = tool.castParams(params)
            val errors = tool.validateParams(castParams)
            if (errors.isNotEmpty()) {
                return "Error: Invalid parameters for tool '$name': ${errors.joinToString("; ")}$hint"
            }
            val result = tool.execute(castParams)
            if (result is String && result.startsWith("Error")) result + hint else result
        } catch (e: Exception) {
            "Error executing $name: ${e.message}$hint"
        }
    }

    val toolNames: List<String> get() = tools.keys.toList()

    val size: Int get() = tools.size

    operator fun contains(name: String): Boolean = name in tools
}
