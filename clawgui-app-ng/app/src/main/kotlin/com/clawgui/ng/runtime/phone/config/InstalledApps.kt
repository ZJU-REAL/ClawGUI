package com.clawgui.ng.runtime.phone.config

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshot of launcher-visible apps on this device.
 *
 * Queried via PackageManager.queryIntentActivities(MAIN+LAUNCHER). Requires the
 * `<queries>` block (already declared in AndroidManifest). Populated once in
 * App.onCreate; callers read the cached snapshot.
 *
 * Matching is tolerant (lowercase + trim, then two-way substring) so the brain
 * LLM can hand back something close to the localized label and we still find it.
 */
object InstalledApps {

    data class Entry(val label: String, val packageName: String)

    private data class Snapshot(
        val entries: List<Entry>,
        val byLabel: Map<String, String>,
    )

    private val snapshot = AtomicReference<Snapshot?>(null)

    /** Safe to call multiple times; later calls just refresh the snapshot. */
    fun init(context: Context) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            return
        }

        val seen = mutableSetOf<String>()
        val list = mutableListOf<Entry>()
        val map = LinkedHashMap<String, String>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = try {
                ri.loadLabel(pm)?.toString()
            } catch (_: Exception) {
                null
            }?.takeIf { it.isNotBlank() } ?: pkg

            list += Entry(label, pkg)
            map.putIfAbsent(label.lowercase().trim(), pkg)
        }
        list.sortBy { it.label.lowercase() }
        snapshot.set(Snapshot(list, map))
    }

    fun findPackage(query: String): String? {
        val snap = snapshot.get() ?: return null
        val needle = query.lowercase().trim()
        if (needle.isEmpty()) return null

        snap.byLabel[needle]?.let { return it }

        // Two-way substring — tolerates "bilibili hd" vs "bilibili", or user
        // typing a prefix. Pick the shortest label that matches to avoid
        // accidentally returning an unrelated app whose label happens to contain
        // a common word.
        return snap.byLabel.entries
            .filter { (label, _) -> needle.contains(label) || label.contains(needle) }
            .minByOrNull { it.key.length }
            ?.value
    }

    fun getAllLabels(): List<String> =
        snapshot.get()?.entries?.map { it.label } ?: emptyList()

    fun getEntries(): List<Entry> =
        snapshot.get()?.entries ?: emptyList()
}
