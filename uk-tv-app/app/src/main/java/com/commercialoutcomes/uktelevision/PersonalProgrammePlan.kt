package com.commercialoutcomes.uktelevision

/** A small programme plan from the existing library; never starts a second player. */
object PersonalProgrammePlan {
    fun candidates(saved:VodSaved,domain:String):List<VodTitle> {
        val pattern=when(domain){"classics"->"classic|comedy|british";"practical"->"practical|homestead|outdoor|ranch|garden";"technology"->"technology|science|engineering|documentary";else->"classic|comedy|british"}
        val lists=saved.lists.filter{it.id!=PersonalQueue.ID && Regex(pattern,RegexOption.IGNORE_CASE).containsMatchIn(it.name)}
        return (lists.flatMap{saved.listTitles(it.id,emptyList())}+saved.titles.filter{it.key in saved.favourites}).distinctBy{it.key}.take(8)
    }
    fun playable(title:VodTitle,allowed:Set<VodProvider>)=title.sources.filter{it.provider in allowed && it.provider!=VodProvider.CREW}
    suspend fun next(title:VodTitle,allowed:Set<VodProvider>,completed:Set<String>,
        load:suspend (VodSource)->List<VodTitle> = {VodEpisodes.load(it)}):VodTitle? {
        val sources=playable(title,allowed)
        if(sources.isEmpty() || title.key in completed)return null
        if(title.kind !in setOf("show","season"))return title.copy(sources=sources)
        // A provider failure must not discard another permitted source. Bound the
        // total series work so one difficult title cannot monopolise the channel.
        var requests=0
        for(source in sources.take(2)) {
            if(Thread.currentThread().isInterrupted)return null
            val rows=runCatching{load(source)}.getOrDefault(emptyList())
            requests++
            rows.firstOrNull{it.kind=="episode" && it.key !in completed && playable(it,allowed).isNotEmpty()}?.let{return it.copy(sources=playable(it,allowed))}
            for(season in rows.filter{it.kind=="season"}) {
                if(requests>=8 || Thread.currentThread().isInterrupted)break
                val seasonSource=playable(season,allowed).firstOrNull() ?: continue
                requests++
                val episodes=runCatching{load(seasonSource)}.getOrDefault(emptyList())
                episodes.firstOrNull{it.kind=="episode" && it.key !in completed && playable(it,allowed).isNotEmpty()}?.let{return it.copy(sources=playable(it,allowed))}
            }
        }
        return null
    }
}
