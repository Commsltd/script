package com.commercialoutcomes.uktelevision

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import java.util.Locale

/** Broadcast composition: picture/rundown, editorial rail, numerical rail. No dashboard cards. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PersonalBroadcastScreen(context:Context,video:PlayerView,status:TextView,controls:LinearLayout,
    private val details:(String)->Unit):LinearLayout(context){
    val title=label(16f,true)
    val clock=label(12f,true).apply{maxLines=3;setTextColor(PersonalStyle.mint);setPadding(d(7),d(7),d(5),d(5))}
    val strip=label(11f).apply{maxLines=1;gravity=Gravity.CENTER_VERTICAL;setTextColor(PersonalStyle.muted)}
    val ticker=PersonalTicker(context)
    val body=LinearLayout(context)
    val left=LinearLayout(context).apply{orientation=VERTICAL}
    val middle=LinearLayout(context).apply{orientation=VERTICAL;setBackgroundColor(Color.rgb(13,18,24))}
    val numerical=LinearLayout(context).apply{orientation=VERTICAL;setBackgroundColor(Color.rgb(13,18,24))}
    val picture=FrameLayout(context)
    val rundown=BroadcastRundown(context,details)
    private val agenda=BroadcastRundown(context,details)
    private val bulletin=BroadcastRundown(context,details)
    private val locationMap=PersonalLocationMap(context).apply{visibility=GONE}
    private val metrics=List(5){BroadcastMetric(context,details)}
    private val railTitle=label(11f,true).apply{setTextColor(Color.BLACK);setBackgroundColor(PersonalStyle.mint);setPadding(d(6),d(3),d(4),d(2))}
    private val dataBand=LinearLayout(context).apply{setBackgroundColor(Color.rgb(28,36,44));gravity=Gravity.CENTER_VERTICAL}
    private val bandTexts=List(3){label(12f,true).apply{maxLines=1;gravity=Gravity.CENTER_VERTICAL;setPadding(d(10),0,d(10),0)}}
    private val newsBand=LinearLayout(context)
    private var pictureOnly=false
    private var proportions=floatArrayOf(56f,27f,17f)
    var domain="business";private set
    init {
        orientation=VERTICAL;setPadding(d(22),d(14),d(22),d(14));setBackgroundColor(PersonalStyle.background)
        val header=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(label(14f,true).apply{text="My Channel";setTextColor(Color.BLACK);setBackgroundColor(PersonalStyle.mint);setPadding(d(8),d(3),d(8),d(3))},LayoutParams(-2,d(25)))
        header.addView(title,LayoutParams(0,-1,1f).apply{leftMargin=d(10)})
        header.addView(label(10f).apply{text="OK  CONTROLS    MENU  YOUR MIX";gravity=Gravity.CENTER_VERTICAL;setTextColor(PersonalStyle.muted)},LayoutParams(-2,-1))
        addView(header,LayoutParams(-1,d(25)).apply{bottomMargin=d(5)})
        picture.addView(video,FrameLayout.LayoutParams(-1,-1));picture.addView(status,FrameLayout.LayoutParams(-1,-1))
        picture.addView(controls,FrameLayout.LayoutParams(-1,d(60),Gravity.BOTTOM))
        left.addView(picture,LayoutParams(-1,0))
        left.addView(rundown,LayoutParams(-1,0,1f).apply{topMargin=d(5)})
        middle.addView(locationMap,LayoutParams(-1,0,34f).apply{bottomMargin=d(5)})
        middle.addView(agenda,LayoutParams(-1,0,72f));middle.addView(bulletin,LayoutParams(-1,0,28f).apply{topMargin=d(5)})
        numerical.addView(railTitle,LayoutParams(-1,d(19)))
        for(metric in metrics)numerical.addView(metric,LayoutParams(-1,0,1f))
        numerical.addView(clock,LayoutParams(-1,d(47)))
        body.addView(left,LayoutParams(0,-1,56f));body.addView(middle,LayoutParams(0,-1,27f).apply{leftMargin=d(5)});body.addView(numerical,LayoutParams(0,-1,17f).apply{leftMargin=d(5)})
        addView(body,LayoutParams(-1,0,1f))
        addView(strip,LayoutParams(-1,d(20)))
        for(t in bandTexts)dataBand.addView(t,LayoutParams(0,-1,1f))
        addView(dataBand,LayoutParams(-1,d(25)))
        newsBand.addView(label(12f,true).apply{text="NEWS";gravity=Gravity.CENTER;setTextColor(Color.BLACK);setBackgroundColor(PersonalStyle.mint)},LayoutParams(d(48),-1))
        newsBand.addView(ticker,LayoutParams(0,-1,1f));addView(newsBand,LayoutParams(-1,d(29)).apply{topMargin=d(3)})
    }
    private fun d(n:Int)=PersonalStyle.dp(context,n)
    private fun label(size:Float,bold:Boolean=false)=PersonalStyle.label(context,size).apply{typeface=Typeface.create("sans-serif-condensed",if(bold)Typeface.BOLD else Typeface.NORMAL);includeFontPadding=false}
    override fun onMeasure(w:Int,h:Int){
        val available=MeasureSpec.getSize(w)-paddingLeft-paddingRight-if(pictureOnly)0 else d(10)
        val width=available*proportions[0]/proportions.sum()
        val lp=picture.layoutParams as LayoutParams
        val targetHeight=if(pictureOnly)0 else (width*9/16).toInt();val targetWeight=if(pictureOnly)1f else 0f
        if(lp.height!=targetHeight || lp.weight!=targetWeight){lp.height=targetHeight;lp.weight=targetWeight;picture.layoutParams=lp}
        super.onMeasure(w,h)
    }
    fun hasInformationFocus()=rundown.hasFocus() || middle.hasFocus() || numerical.hasFocus()
    fun pictureOnly(value:Boolean){pictureOnly=value;middle.visibility=if(value)GONE else VISIBLE;numerical.visibility=middle.visibility;rundown.visibility=middle.visibility;dataBand.visibility=middle.visibility;requestLayout()}
    fun tickerVisible(value:Boolean){newsBand.visibility=if(value && !pictureOnly)VISIBLE else GONE}
    fun bind(domain:String,rawData:Map<String,PersonalPanel>,page:Int){
        val data=rawData.toMutableMap()
        val sources=when(domain){"business"->listOf("bbc-business","guardian-business");"world"->listOf("bbc-world","guardian-world");"spain"->listOf("spain-elpais","spain-rtve");"space"->listOf("nasa");"practical"->listOf("guardian-gardens","bbc-science");else->listOf("bbc-tech","guardian-tech")}
        data["brief"]=PersonalEditorial.brief(data,sources,page)
        data["context"]=PersonalEditorial.currencyContext(data["fx"])
        this.domain=domain
        val weights=when(domain){"world"->floatArrayOf(58f,25f,17f);"spain"->floatArrayOf(57f,26f,17f);"space"->floatArrayOf(58f,25f,17f);"classics"->floatArrayOf(59f,25f,16f);"practical"->floatArrayOf(57f,26f,17f);else->floatArrayOf(56f,27f,17f)}
        if(!weights.contentEquals(proportions)){proportions=weights;listOf(left,middle,numerical).forEachIndexed{i,v->(v.layoutParams as LayoutParams).weight=weights[i]};requestLayout()}
        fun entries(vararg ids:String,body:Boolean=false):List<BroadcastEntry> = ids.flatMap{id->data[id]?.let{panel->panel.items.map{item->BroadcastEntry(item.headline,if(body)item.body else "",meta(panel,item),detail(panel,item))}} ?: emptyList()}
        fun rotating(items:List<BroadcastEntry>,count:Int):List<BroadcastEntry> = if(items.size<=count)items else (0 until count).map{items[(page*count+it)%items.size]}
        fun list(view:BroadcastRundown,title:String,items:List<BroadcastEntry>,count:Int,body:Boolean=false)=view.bind(title,rotating(items,count),body)
        val mapped=domain=="world" || domain=="space"
        locationMap.visibility=if(mapped)VISIBLE else GONE
        (agenda.layoutParams as LayoutParams).weight=if(mapped)38f else 72f
        var metricItems=emptyList<Pair<PersonalPanel,PersonalItem>>()
        when(domain){
            "business"->{
                list(rundown,"IN FOCUS · BUSINESS",entries("brief",body=true),1,true)
                if(page%2==0)list(agenda,"NEXT · UK ECONOMIC CALENDAR",entries("calendar",body=true),3,true) else list(agenda,"CURRENCY PERSPECTIVE",entries("context",body=true),2,true)
                list(bulletin,"POLICY & ECONOMIC BULLETINS",entries("boe","releases",body=true),1,true)
                railTitle.text="MARKETS & CURRENCIES";metricItems=listOf("ftse","sp500","ibex").mapNotNull{id->data[id]?.takeIf{it.available}?.let{it to it.items.first()}};data["fx"]?.let{p->metricItems+=p.items.take(2).map{p to it}}
                bandTexts[0].text="REFERENCE  /  ${data["fx"]?.items?.firstOrNull()?.observed ?: "CONNECTING"}"
                bandTexts[1].text="INDICES · DELAYED QUOTES";bandTexts[2].text="FX · DAILY ECB REFERENCE"
            }
            "world"->{
                list(rundown,"IN FOCUS · PUBLISHER BRIEFING",entries("brief",body=true),1,true)
                list(agenda,"YOUR WINDOW",entries("picture",body=true),1,true)
                list(bulletin,"EARTH WATCH",entries("quakes"),1)
                railTitle.text="LOCAL CONDITIONS"
                data.values.firstOrNull{it.id in setOf("yellowstone","truckee","scripps","greenhorn")}?.let{p->metricItems=p.items.map{p to it}}
                val weather=metricItems.firstOrNull()?.first
                val point=when(weather?.id ?: data["picture"]?.items?.firstOrNull()?.headline?.let{if(it.startsWith("Truckee"))"truckee" else if(it.startsWith("Scripps"))"scripps" else if(it.startsWith("Greenhorn"))"greenhorn" else "yellowstone"}){"truckee"->39.33 to -120.18;"scripps"->32.87 to -117.26;"greenhorn"->39.88 to -120.79;else->44.46 to -110.83}
                locationMap.position(point.first,point.second,"CAMERA · ${weather?.id?.uppercase(Locale.ROOT) ?: "YELLOWSTONE"}")
                bandTexts[0].text=weather?.items?.firstOrNull()?.headline ?: "LOCAL WEATHER CONNECTING"
                bandTexts[1].text=weather?.items?.firstOrNull()?.body ?: "WEATHER · OPEN-METEO"
                bandTexts[2].text="EARTH OBSERVATIONS · USGS"
            }
            "spain"->{
                list(rundown,"IN FOCUS · PUBLISHER BRIEFING",entries("brief",body=true),1,true)
                list(agenda,"ALSO IN SPAIN",entries("spain-rtve",body=true),2,true)
                list(bulletin,"AEMET · SPAIN WARNINGS",entries("aemet",body=true),1,true)
                railTitle.text="MADRID · FORECAST";data["madrid"]?.let{p->metricItems=p.items.map{p to it}}
                bandTexts[0].text="MADRID · "+(data["madrid"]?.items?.firstOrNull()?.headline ?: "CONNECTING");bandTexts[1].text="SEVILLE · "+(data["seville"]?.items?.firstOrNull()?.headline ?: "CONNECTING");bandTexts[2].text="WEATHER · OPEN-METEO"
            }
            "space"->{
                list(rundown,"IN FOCUS · PUBLISHER BRIEFING",entries("brief",body=true),1,true)
                list(agenda,"NEXT · SPACE LAUNCHES",entries("launches",body=true),2,true)
                val position=data["iss"]?.items.orEmpty()
                locationMap.position(position.find{it.metric=="LATITUDE"}?.number?.toDoubleOrNull(),position.find{it.metric=="LONGITUDE"}?.number?.toDoubleOrNull(),"ISS · "+(position.firstOrNull()?.observed ?: "CONNECTING"))
                list(bulletin,"ORBITAL CONDITIONS",entries("iss").take(1),1)
                railTitle.text="SPACE WEATHER";data["kp"]?.let{p->metricItems=listOf(p to p.items.first().copy(history=p.series,metric="PLANETARY Kp",number=p.items.first().headline.substringAfterLast(' '))) }
                data["iss"]?.let{p->metricItems+=p.items.drop(1).map{p to it}}
                bandTexts[0].text="ISS · ${data["iss"]?.items?.firstOrNull()?.headline?.substringAfter("· ")?.uppercase(Locale.ROOT) ?: "CONNECTING"}"
                bandTexts[1].text="Kp SCALE 0–9 · NOAA";bandTexts[2].text="POSITION & SPACE WEATHER · UTC"
            }
            "classics"->{
                list(rundown,"NOW WATCHING",entries("onair",body=true).take(1),1,true)
                list(agenda,"DISCOVER FROM MY LISTS",entries("library",body=true),2,true)
                list(bulletin,"YOUR PROGRAMMING",entries("rundown",body=true),1,true)
                railTitle.text="ON THIS CHANNEL · NEXT";data["onair"]?.let{p->metricItems=p.items.drop(1).map{p to it}}
                if(metricItems.isEmpty())data["rundown"]?.let{p->metricItems=p.items.map{p to it}}
                bandTexts[0].text="BRITISH COMEDY & DRAMA";bandTexts[1].text="MY LISTS · YOUR SAVED PROGRAMMES";bandTexts[2].text="REFERENCE · TVMAZE / CC BY-SA"
            }
            "practical"->{
                list(rundown,"IN FOCUS · PUBLISHER BRIEFING",entries("brief",body=true),1,true)
                list(agenda,"PRACTICAL READING",entries("guardian-gardens",body=true),2,true)
                list(bulletin,"ON THIS CHANNEL",entries("onair",body=true),1,true)
                railTitle.text="OUTDOOR CONDITIONS";data["seville"]?.let{p->metricItems=p.items.map{p to it}}
                bandTexts[0].text="HOMESTEADING · GARDENS · OUTDOORS";bandTexts[1].text="YOUR QUEUE · MENU → MY LISTS";bandTexts[2].text="PROGRAMME REFERENCE · TVMAZE"
            }
            else->{
                list(rundown,"IN FOCUS · PUBLISHER BRIEFING",entries("brief",body=true),1,true)
                list(agenda,"RESEARCH · WHAT THE PAPER STUDIES",entries("research",body=true),2,true)
                list(bulletin,"TOOL UPDATE · RELEASE NOTES",entries("ollama-releases","node-releases","media3-releases",body=true),1,true)
                railTitle.text="RELEASE WATCH"
                metricItems=listOf("github-releases" to "VS CODE","node-releases" to "NODE.JS","ollama-releases" to "OLLAMA","media3-releases" to "ANDROID MEDIA3").mapNotNull{(id,label)->data[id]?.let{p->p to p.items.first().copy(metric=label,number=p.items.first().headline,body=p.items.first().observed)}}
                bandTexts[0].text="TECHNOLOGY & AI";bandTexts[1].text="RESEARCH · arXiv cs.AI";bandTexts[2].text="RELEASES · OFFICIAL GITHUB"
            }
        }
        metrics.forEachIndexed{i,v->v.visibility=if(i<metricItems.size || (i==0 && metricItems.isEmpty()))VISIBLE else GONE;val pair=metricItems.getOrNull(i);v.bind(pair?.first,pair?.second)}
    }
    private fun meta(p:PersonalPanel,i:PersonalItem)=(if(!p.available)"UNAVAILABLE · " else if(p.stale)"CACHED · " else "")+(if(p.id=="releases")"ONS" else p.source)+" · "+i.observed.ifBlank{PersonalPanelParser.utc(p.fetchedAt)}
    private fun detail(p:PersonalPanel,i:PersonalItem)=i.headline+"\n\n"+i.body+"\n\n"+meta(p,i)+"\nRetrieved "+PersonalPanelParser.utc(p.fetchedAt)+"\n"+i.url
}

data class BroadcastEntry(val title:String,val body:String="",val meta:String="",val detail:String="")
class BroadcastRundown(context:Context,private val details:(String)->Unit):LinearLayout(context){
    private val heading=PersonalStyle.label(context,12f,PersonalStyle.mint).apply{typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);maxLines=1;setPadding(d(7),d(3),d(4),d(2))}
    private val rows=List(4){Row(context,details)}
    init{orientation=VERTICAL;setBackgroundColor(Color.rgb(13,18,24));addView(heading,LayoutParams(-1,d(21)));rows.forEach{addView(it,LayoutParams(-1,0,1f))}}
    private fun d(n:Int)=PersonalStyle.dp(context,n)
    fun bind(title:String,items:List<BroadcastEntry>,body:Boolean){heading.text=title;rows.forEachIndexed{i,r->r.visibility=if(i<items.size || (i==0 && items.isEmpty()))VISIBLE else GONE;r.bind(items.getOrNull(i) ?: BroadcastEntry("Connecting…","","Independent feed · picture continues"),body,items.size)}}
    private class Row(context:Context,details:(String)->Unit):LinearLayout(context){
        private val title=PersonalStyle.label(context,14f).apply{typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);ellipsize=android.text.TextUtils.TruncateAt.END}
        private val description=PersonalStyle.label(context,13f,PersonalStyle.muted)
        private val origin=PersonalStyle.label(context,9f,PersonalStyle.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        private var sourceDetail=""
        private var desiredTitleLines=2
        private var showBody=false
        init{orientation=VERTICAL;isFocusable=true;isClickable=true;background=PersonalStyle.focusBackground(context);setPadding(d(7),d(3),d(7),d(3));addView(title);addView(description,LayoutParams(-1,-2).apply{topMargin=d(3)});addView(origin,LayoutParams(-1,-2).apply{topMargin=d(3)});setOnClickListener{details(sourceDetail)}}
        private fun d(n:Int)=PersonalStyle.dp(context,n)
        override fun onMeasure(w:Int,h:Int){
            if(MeasureSpec.getMode(h)==MeasureSpec.EXACTLY){
                val room=(MeasureSpec.getSize(h)-paddingTop-paddingBottom-origin.lineHeight-d(3)).coerceAtLeast(title.lineHeight)
                val titleLines=minOf(desiredTitleLines,((room-if(showBody)description.lineHeight+d(3) else 0)/title.lineHeight).coerceAtLeast(1))
                title.maxLines=titleLines
                title.measure(MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(w)-paddingLeft-paddingRight).coerceAtLeast(1),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
                val bodyLines=((room-title.measuredHeight-d(3))/description.lineHeight).coerceIn(0,4)
                description.visibility=if(showBody && bodyLines>0)VISIBLE else GONE
                description.maxLines=bodyLines.coerceAtLeast(1);description.ellipsize=android.text.TextUtils.TruncateAt.END
            }
            super.onMeasure(w,h)
        }
        fun bind(e:BroadcastEntry,body:Boolean,count:Int){title.text=e.title;title.textSize=if(count>=4)13.5f else if(count>=2)14f else 16f;desiredTitleLines=if(count==1)4 else if(count>=3)3 else 2;showBody=body && e.body.isNotBlank();title.maxLines=desiredTitleLines;description.text=e.body;description.maxLines=if(count==1)4 else 3;description.visibility=if(body && e.body.isNotBlank())VISIBLE else GONE;origin.text=e.meta;sourceDetail=e.detail.ifBlank{e.title+"\n"+e.body+"\n"+e.meta};contentDescription=e.title+". "+e.meta}
    }
}
class BroadcastMetric(context:Context,private val details:(String)->Unit):LinearLayout(context){
    private val label=PersonalStyle.label(context,9f,PersonalStyle.mint)
    private val value=PersonalStyle.label(context,18f).apply{includeFontPadding=false;typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);maxLines=2}
    private val change=PersonalStyle.label(context,9f).apply{includeFontPadding=false}
    private val chart=PersonalSparkline(context)
    private val note=PersonalStyle.label(context,8f,PersonalStyle.muted).apply{includeFontPadding=false;maxLines=2}
    private var text=""
    init{orientation=VERTICAL;setPadding(d(6),d(2),d(6),d(2));background=PersonalStyle.focusBackground(context);isFocusable=true;isClickable=true;addView(label);addView(value);addView(change);addView(chart,LayoutParams(-1,0,1f));addView(note);setOnClickListener{details(text)}}
    private fun d(n:Int)=PersonalStyle.dp(context,n)
    fun bind(p:PersonalPanel?,i:PersonalItem?){
        label.text=i?.metric?.ifBlank{p?.title?.uppercase(Locale.ROOT)} ?: "DATA FEED"
        value.text=i?.number?.ifBlank{i.headline} ?: "Connecting…";value.textSize=if(i?.number?.isNotBlank()==true)18f else 14f
        val delta=i?.change;change.visibility=if(delta==null)GONE else VISIBLE
        if(delta!=null){change.text=String.format(Locale.UK,"%+.2f%%  %s",delta,if(p?.id in setOf("ftse","sp500","ibex"))"vs prior close" else "daily");change.setTextColor(if(delta>=0)Color.rgb(65,214,156)else Color.rgb(255,100,91))}
        chart.visibility=if((i?.history?.size ?: 0)>1)VISIBLE else GONE;if(chart.visibility==VISIBLE)chart.setPoints(i!!.history)
        note.text=if(i?.history?.isNotEmpty()==true)i.observed else i?.body?.ifBlank{i.observed} ?: "";note.visibility=if(note.text.isBlank())GONE else VISIBLE
        text=(i?.headline ?: "Connecting")+"\n\n"+(i?.body ?: "")+"\n\n"+(p?.source ?: "")+" · "+(i?.observed ?: "")+"\n"+(i?.url ?: "")
        contentDescription=text
    }
}
