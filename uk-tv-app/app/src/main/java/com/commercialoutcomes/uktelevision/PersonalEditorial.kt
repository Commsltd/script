package com.commercialoutcomes.uktelevision

import java.util.Locale

/** Source-grounded programme editing. No generated claims or invented market explanations. */
object PersonalEditorial {
    fun words(title:String)=title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]")," ")
        .split(Regex("\\s+")).filter{it.length>3}.toSet()
    fun distinct(items:List<PersonalItem>):List<PersonalItem> {
        val kept=mutableListOf<PersonalItem>()
        for(item in items){
            val w=words(item.headline)
            if(kept.none{old->val other=words(old.headline);old.headline.equals(item.headline,true) || (w.isNotEmpty() && w.intersect(other).size.toDouble()/maxOf(w.size,other.size)>=0.65)})kept+=item
        }
        return kept.take(16)
    }
    fun brief(data:Map<String,PersonalPanel>,ids:List<String>,page:Int):PersonalPanel {
        val items=distinct(ids.flatMap{id->data[id]?.takeIf{it.available}?.let{p->p.items.map{it.copy(observed=p.source+" · "+it.observed+(if(p.stale)" · cached" else ""))}}.orEmpty()})
        return PersonalPanel("brief","In focus","Publisher summary",if(items.isEmpty()) listOf(PersonalItem("Waiting for this briefing","The picture continues while sources reconnect.")) else listOf(items[Math.floorMod(page,items.size)]),available=items.isNotEmpty())
    }
    fun currencyContext(panel:PersonalPanel?):PersonalPanel {
        val items=panel?.items.orEmpty().take(2).map{i->
            val h=i.history;val current=h.lastOrNull();val first=h.firstOrNull()
            val change=if(current!=null && first!=null && first>0)(current/first-1)*100 else null
            val text=if(change==null) "History unavailable" else String.format(Locale.UK,"%+.2f%% across %d daily observations",change,h.size)
            i.copy(headline=i.metric.ifBlank{i.headline},body=text+". Daily reference rates; bank fees and spreads are excluded.",number="",history=emptyList(),change=null)
        }
        return PersonalPanel("context","Currency perspective","ECB",items.ifEmpty{listOf(PersonalItem("Currency history unavailable"))},stale=panel?.stale ?: true,available=items.isNotEmpty())
    }
}
