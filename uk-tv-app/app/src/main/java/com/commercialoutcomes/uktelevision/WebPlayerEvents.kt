package com.commercialoutcomes.uktelevision

import org.json.JSONArray
import org.json.JSONTokener

object WebPlayerEvents {
    fun parse(raw: String): List<Pair<String, Int>> = runCatching {
        require(raw.length <= 4096)
        val decoded = JSONTokener(raw).nextValue() as? String ?: return emptyList()
        val rows = JSONArray(decoded)
        require(rows.length() <= 12)
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONArray(index) ?: return@mapNotNull null
            val name = row.optString(0)
            val code = row.opt(1) as? Int ?: return@mapNotNull null
            if (name in setOf("state", "error", "blocked", "ready") && code in -1..1000) name to code else null
        }
    }.getOrDefault(emptyList())
}
