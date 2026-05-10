package com.clawgui.android.core.nano.agent

import java.io.File

class SkillsLoader(private val workspace: File) {
    private val workspaceSkillsDir = File(workspace, "skills")

    fun listSkills(): List<Map<String, String>> {
        if (!workspaceSkillsDir.exists()) return emptyList()
        return workspaceSkillsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").exists() }
            ?.map { dir ->
                mapOf("name" to dir.name, "path" to File(dir, "SKILL.md").absolutePath, "source" to "workspace")
            }
            ?: emptyList()
    }

    fun loadSkill(name: String): String? {
        val f = File(workspaceSkillsDir, "$name/SKILL.md")
        return if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    fun loadSkillsForContext(skillNames: List<String>): String =
        skillNames.mapNotNull { name ->
            loadSkill(name)?.let { content -> "### Skill: $name\n\n${stripFrontmatter(content)}" }
        }.joinToString("\n\n---\n\n")

    fun buildSkillsSummary(): String {
        val skills = listSkills()
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("<skills>")
            for (s in skills) {
                val name = escapeXml(s["name"] ?: "")
                val path = s["path"] ?: ""
                val desc = escapeXml(getSkillDescription(s["name"] ?: ""))
                appendLine("  <skill available=\"true\">")
                appendLine("    <name>$name</name>")
                appendLine("    <description>$desc</description>")
                appendLine("    <location>$path</location>")
                appendLine("  </skill>")
            }
            append("</skills>")
        }
    }

    fun getAlwaysSkills(): List<String> =
        listSkills().filter { s ->
            getSkillMetadata(s["name"] ?: "")?.get("always") == "true"
        }.mapNotNull { it["name"] }

    fun getSkillMetadata(name: String): Map<String, String>? {
        val content = loadSkill(name) ?: return null
        if (!content.startsWith("---")) return null
        val match = Regex("^---\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL).find(content) ?: return null
        return match.groupValues[1].split("\n").mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0) line.substring(0, colon).trim() to line.substring(colon + 1).trim().trim('"', '\'')
            else null
        }.toMap()
    }

    private fun getSkillDescription(name: String): String =
        getSkillMetadata(name)?.get("description") ?: name

    private fun stripFrontmatter(content: String): String {
        if (!content.startsWith("---")) return content
        val end = Regex("^---\\n.*?\\n---\\n", RegexOption.DOT_MATCHES_ALL).find(content)
            ?: return content
        return content.substring(end.range.last + 1).trim()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
