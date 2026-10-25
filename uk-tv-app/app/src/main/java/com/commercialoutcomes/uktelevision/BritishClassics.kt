package com.commercialoutcomes.uktelevision

import org.json.JSONObject

/** Curated metadata and lookup routes, not guarantees of playable episodes. */
object BritishClassics {
    const val MIGRATION = "british-classics-lists-v1"
    data class Collection(val id: String, val name: String, val titles: List<VodTitle>, val notes: Map<String,String> = emptyMap())

    fun collections(raw: String): List<Collection> {
        val lists = JSONObject(raw).getJSONArray("lists")
        return (0 until lists.length()).map { i ->
            val item = lists.getJSONObject(i)
            val type=item.optString("type","tv")
            val titles = VodDiscovery.parse(item, type).titles.map { title ->
                val id = java.net.URI(title.sources.first().url).path.substringAfterLast('/')
                title.copy(sources = title.sources + VodSource(VodProvider.CREW, "crew://$type/$id"))
            }
            val results=item.getJSONArray("results")
            val notes=(0 until results.length()).mapNotNull { n ->
                val row=results.getJSONObject(n);val note=row.optString("note")
                val title=titles.firstOrNull { it.sources.any { source -> source.url=="crew://$type/${row.optLong("id")}" } }
                if(note.isBlank()||title==null)null else title.key to note
            }.toMap()
            Collection(item.getString("id"), item.getString("name"), titles, notes)
        }
    }

    /** Run once per library. After installation these are ordinary editable lists. */
    fun install(saved: VodSaved, collections: List<Collection>, alreadyInstalled: Boolean): VodSaved {
        if (alreadyInstalled) return saved
        var next = saved
        for (collection in collections) {
            val existing = next.lists.firstOrNull { it.id == collection.id || it.name.equals(collection.name, true) }
            val target = existing ?: VodList(collection.id, collection.name)
            var keys = target.keys
            var notes = target.notes
            for (title in collection.titles) {
                next = next.remember(title)
                keys = keys + next.keyFor(title)
                collection.notes[title.key]?.let { notes=notes+(next.keyFor(title) to it) }
            }
            val updated = target.copy(keys = keys, notes = notes)
            next = next.copy(lists = if (existing == null) next.lists + updated else next.lists.map { if (it.id == target.id) updated else it })
        }
        return next
    }

    fun query(value: String): String = when(normalTitle(value)) {
        "ofah" -> "Only Fools and Horses"
        "morcombe and wise", "morecambe and wise", "morecambe wise" -> "The Morecambe and Wise Show"
        else -> value.trim()
    }
}
