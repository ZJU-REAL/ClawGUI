package com.clawgui.ng.runtime.phone

import com.clawgui.ng.data.Plan
import com.clawgui.ng.data.PlanItem
import com.clawgui.ng.data.PlanItemStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parse + apply the PhoneAgent plan protocol.
 *
 * Each model step *may* include a `<plan>...</plan>` block alongside the
 * mandatory `<think>` and `<answer>` blocks. The block contains a JSON
 * object `{ "ops": [ ... ] }` where each op mutates the running plan:
 *
 *   - `init`         — initialise the plan (first step only)
 *   - `update`       — change an item's status / note
 *   - `insert_after` — splice a new item after an existing id
 *   - `remove`       — drop an item (model realised it's not needed)
 *
 * The protocol is intentionally fault-tolerant:
 *   - Missing / malformed `<plan>` block ⇒ plan stays as-is, agent keeps
 *     running. Plan UI is decorative; never gates execution.
 *   - Unknown statuses fall back to PENDING.
 *   - Updating a non-existent id is a no-op (logged).
 *
 * The block is stripped from the raw response *before* AutoGLM parses the
 * action, so it never interferes with the action grammar.
 */
object PlanProtocol {

    private val BLOCK_RE = Regex("""<plan>\s*(.*?)\s*</plan>""", RegexOption.DOT_MATCHES_ALL)
    private val json = Json { ignoreUnknownKeys = true }

    /** Extract the plan JSON body if present. Returns the body or null. */
    fun extract(raw: String): String? = BLOCK_RE.find(raw)?.groupValues?.get(1)?.trim()

    /** Strip the `<plan>` block from a model response so downstream parsers
     *  see only `<think>` + `<answer>`. */
    fun stripBlock(raw: String): String = BLOCK_RE.replace(raw, "").trim()

    /**
     * Apply a plan JSON body against [previous] (null = no plan yet). Returns
     * the new Plan, or [previous] unchanged if the body is empty / malformed.
     * [stepIndex] is stamped on every touched item for animation ordering.
     */
    fun apply(previous: Plan?, body: String?, stepIndex: Int): Plan? {
        if (body.isNullOrBlank()) return previous
        val root = runCatching {
            json.parseToJsonElement(body).jsonObject
        }.getOrNull() ?: return previous
        val ops = (root["ops"] as? JsonArray) ?: return previous

        var current: Plan? = previous
        ops.forEach { opEl ->
            val op = opEl as? JsonObject ?: return@forEach
            current = applyOne(current, op, stepIndex)
        }
        // Recompute activeItemId from the items themselves so the model can't
        // forget to set it: pick the first IN_PROGRESS, then the first
        // BLOCKED, then null. Anything the model explicitly set in an op is
        // overruled here — single source of truth.
        val c = current ?: return null
        val active = c.items.firstOrNull { it.status == PlanItemStatus.IN_PROGRESS }?.id
            ?: c.items.firstOrNull { it.status == PlanItemStatus.BLOCKED }?.id
        return c.copy(activeItemId = active)
    }

    private fun applyOne(previous: Plan?, op: JsonObject, stepIndex: Int): Plan? {
        return when ((op["op"] as? JsonPrimitive)?.content) {
            "init" -> {
                val itemsEl = op["items"] as? JsonArray ?: return previous
                val items = itemsEl.mapNotNull { parseItem(it as? JsonObject, stepIndex) }
                if (items.isEmpty()) previous else Plan(items = items)
            }
            "update" -> {
                val target = previous ?: return previous
                val id = (op["id"] as? JsonPrimitive)?.content ?: return previous
                val status = parseStatus((op["status"] as? JsonPrimitive)?.content)
                val note = (op["note"] as? JsonPrimitive)?.content
                val newItems = target.items.map { item ->
                    if (item.id != id) item
                    else item.copy(
                        status = status ?: item.status,
                        note = note ?: item.note,
                        updatedAtStep = stepIndex,
                    )
                }
                target.copy(items = newItems)
            }
            "insert_after" -> {
                val target = previous ?: return previous
                val afterId = (op["after"] as? JsonPrimitive)?.content ?: return previous
                val newItem = parseItem(op["item"] as? JsonObject, stepIndex) ?: return previous
                val idx = target.items.indexOfFirst { it.id == afterId }
                if (idx < 0) return target.copy(items = target.items + newItem)
                val before = target.items.subList(0, idx + 1)
                val after = target.items.subList(idx + 1, target.items.size)
                target.copy(items = before + newItem + after)
            }
            "remove" -> {
                val target = previous ?: return previous
                val id = (op["id"] as? JsonPrimitive)?.content ?: return previous
                target.copy(items = target.items.filter { it.id != id })
            }
            else -> previous
        }
    }

    private fun parseItem(obj: JsonObject?, stepIndex: Int): PlanItem? {
        if (obj == null) return null
        val id = (obj["id"] as? JsonPrimitive)?.content ?: return null
        val title = (obj["title"] as? JsonPrimitive)?.content ?: return null
        val detail = (obj["detail"] as? JsonPrimitive)?.content
        val status = parseStatus((obj["status"] as? JsonPrimitive)?.content) ?: PlanItemStatus.PENDING
        val note = (obj["note"] as? JsonPrimitive)?.content
        return PlanItem(
            id = id.trim().take(40).ifBlank { return null },
            title = title.trim().take(40),
            detail = detail?.trim()?.take(120)?.takeIf { it.isNotBlank() },
            status = status,
            note = note?.trim()?.take(120)?.takeIf { it.isNotBlank() },
            updatedAtStep = stepIndex,
        )
    }

    private fun parseStatus(raw: String?): PlanItemStatus? {
        if (raw.isNullOrBlank()) return null
        return runCatching { PlanItemStatus.valueOf(raw.trim().uppercase()) }.getOrNull()
    }
}
