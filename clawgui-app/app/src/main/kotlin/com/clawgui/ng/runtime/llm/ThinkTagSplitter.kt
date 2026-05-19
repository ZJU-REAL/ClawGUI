package com.clawgui.ng.runtime.llm

/**
 * Incremental splitter that separates inline reasoning (`<think>...</think>`)
 * out of a delta stream so chat UI can route the two parts to different
 * surfaces (collapsible "思考过程" vs. main bubble).
 *
 * Designed for the case where reasoning_content is NOT exposed by the
 * provider as a sibling SSE field — many self-hosted / fine-tuned models
 * (and 智谱 GLM-5.1) inline the trace via XML-style tags. Some streams
 * even emit half-tag chunks ("<thi" then "nk>") so we buffer carefully.
 *
 * Usage:
 *   val sp = ThinkTagSplitter()
 *   onDelta(text)  -> sp.push(text)  →  Out(thinking="…", content="…")
 *
 * State invariant: when [inside] is true, every char goes to thinking
 * until we see `</think>`. Tag-bytes themselves never leak to either
 * stream. Untagged content streams through unchanged.
 */
class ThinkTagSplitter {

    private val pending = StringBuilder()
    private var inside = false

    data class Out(val thinking: String, val content: String) {
        fun isEmpty(): Boolean = thinking.isEmpty() && content.isEmpty()
    }

    /**
     * Push the next delta chunk. Returns the *new* thinking and content
     * pieces (deltas) — callers append these to their own accumulators.
     */
    fun push(chunk: String): Out {
        pending.append(chunk)
        val thinkOut = StringBuilder()
        val mainOut = StringBuilder()

        while (pending.isNotEmpty()) {
            if (inside) {
                val close = pending.indexOf(CLOSE_TAG)
                if (close < 0) {
                    // No close tag yet — emit everything that can't possibly
                    // be the start of the closing tag, hold back a small
                    // suffix in case </think is split across deltas.
                    val safe = (pending.length - CLOSE_TAG.length + 1).coerceAtLeast(0)
                    if (safe > 0) {
                        thinkOut.append(pending, 0, safe)
                        pending.delete(0, safe)
                    }
                    break
                } else {
                    thinkOut.append(pending, 0, close)
                    pending.delete(0, close + CLOSE_TAG.length)
                    inside = false
                }
            } else {
                val open = pending.indexOf(OPEN_TAG)
                if (open < 0) {
                    val safe = (pending.length - OPEN_TAG.length + 1).coerceAtLeast(0)
                    if (safe > 0) {
                        mainOut.append(pending, 0, safe)
                        pending.delete(0, safe)
                    }
                    break
                } else {
                    mainOut.append(pending, 0, open)
                    pending.delete(0, open + OPEN_TAG.length)
                    inside = true
                }
            }
        }
        return Out(thinkOut.toString(), mainOut.toString())
    }

    /** Flush whatever's still buffered when the stream finishes — emits as
     *  the trailing chunk of whichever segment we're currently inside. */
    fun finish(): Out {
        if (pending.isEmpty()) return Out("", "")
        val tail = pending.toString()
        pending.clear()
        return if (inside) Out(tail, "") else Out("", tail)
    }

    private companion object {
        const val OPEN_TAG = "<think>"
        const val CLOSE_TAG = "</think>"
    }
}
