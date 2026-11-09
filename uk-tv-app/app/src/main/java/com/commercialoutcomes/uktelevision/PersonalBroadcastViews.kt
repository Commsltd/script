package com.commercialoutcomes.uktelevision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils

object PersonalStyle {
    val background=Color.rgb(8,18,28);val panel=Color.rgb(17,34,47);val foreground=Color.rgb(236,244,246);val muted=Color.rgb(156,180,193);val mint=Color.rgb(91,222,191)
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density).toInt()
    fun focusBackground(c:Context):StateListDrawable {
        fun shape(focused:Boolean)=GradientDrawable().apply{setColor(if(focused) Color.rgb(28,65,77) else panel);cornerRadius=dp(c,5).toFloat();setStroke(dp(c,if(focused)2 else 1),if(focused)mint else Color.rgb(32,53,67))}
        return StateListDrawable().apply{addState(intArrayOf(android.R.attr.state_focused),shape(true));addState(intArrayOf(android.R.attr.state_pressed),shape(true));addState(intArrayOf(),shape(false))}
    }
    fun label(c:Context,size:Float,color:Int=foreground)=TextView(c).apply{textSize=size;setTextColor(color);includeFontPadding=false}
    fun action(c:Context,text:String,click:()->Unit)=label(c,16f).apply{this.text=text;gravity=Gravity.CENTER;isFocusable=true;isClickable=true;background=focusBackground(c);setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));setOnClickListener{click()}}
}
class PersonalCard(context:Context):LinearLayout(context) {
    val heading=PersonalStyle.label(context,13f,PersonalStyle.mint)
    val value=PersonalStyle.label(context,19f)
    val detail=PersonalStyle.label(context,14f,PersonalStyle.muted)
    val origin=PersonalStyle.label(context,11f,PersonalStyle.muted)
    private val graph=PersonalSparkline(context)
    var detailText=""
    init {
        orientation=VERTICAL;setPadding(d(12),d(9),d(12),d(8));background=PersonalStyle.focusBackground(context);isFocusable=true;isClickable=true
        heading.maxLines=1;heading.ellipsize=TextUtils.TruncateAt.END;value.maxLines=3;value.ellipsize=TextUtils.TruncateAt.END
        detail.maxLines=1;detail.ellipsize=TextUtils.TruncateAt.END;origin.maxLines=1;origin.ellipsize=TextUtils.TruncateAt.END
        addView(heading);addView(value,LayoutParams(-1,0,1f).apply{topMargin=d(5)});addView(graph,LayoutParams(-1,d(28)));addView(detail);addView(origin,LayoutParams(-1,-2).apply{topMargin=d(4)})
    }
    private fun d(n:Int)=PersonalStyle.dp(context,n)
    fun show(panel:PersonalPanel,index:Int,chart:Boolean=false){
        val item=panel.items[index.mod(panel.items.size)]
        heading.text=panel.title.uppercase(java.util.Locale.ROOT)
        value.text=item.headline
        detail.text=item.body.take(150)
        detail.visibility=if(item.body.isBlank() || chart)GONE else VISIBLE
        origin.text=(if(panel.stale) "CACHED · " else "")+panel.source+" · "+item.observed.ifBlank{PersonalPanelParser.utc(panel.fetchedAt)}
        graph.visibility=if(chart && panel.series.size>1)VISIBLE else GONE
        if(graph.visibility==VISIBLE){value.maxLines=1;graph.setPoints(panel.series);detail.text=panel.seriesLabel;detail.visibility=VISIBLE}else value.maxLines=if(height>d(150))3 else 2
        detailText=panel.title+"\n\n"+item.headline+"\n\n"+item.body+"\n\n"+origin.text+"\nFetched "+PersonalPanelParser.utc(panel.fetchedAt)+"\n"+item.url
        contentDescription=panel.title+". "+item.headline+". "+origin.text
    }
}
class PersonalSparkline(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=PersonalStyle.mint;strokeWidth=2*resources.displayMetrics.density;style=Paint.Style.STROKE}
    private val path=Path();private var points=emptyList<Double>()
    fun setPoints(values:List<Double>){points=values.takeLast(40);rebuild();invalidate()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){rebuild()}
    private fun rebuild(){path.reset();if(points.size<2)return;val lo=points.min();val span=(points.max()-lo).coerceAtLeast(0.000001);points.forEachIndexed{i,v->val x=i*(width-4f)/(points.size-1)+2;val y=height-3f-((v-lo)/span*(height-6f)).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}}
    override fun onDraw(c:Canvas){super.onDraw(c);c.drawPath(path,paint)}
}
/** Single cached text run; no per-frame layout, network, or allocations. */
class PersonalTicker(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=PersonalStyle.foreground;textSize=18*resources.displayMetrics.scaledDensity}
    private var line="";private var lineWidth=0f;private var offset=0f;private var last=0L
    private var running=false;var held=false;var reducedMotion=false;var speed=32f
    init {isFocusable=true;isClickable=true;setBackgroundColor(Color.rgb(18,43,57))}
    fun setHeadlines(text:String){val next=text.take(3500);if(next!=line){line=next;lineWidth=paint.measureText(line);offset=0f;contentDescription=text;invalidate()}}
    fun start(){running=true;last=0;invalidate()}
    fun stop(){running=false;last=0}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);val now=SystemClock.uptimeMillis();val gap=width.coerceAtLeast(1)*0.25f
        if(running && !held && !hasFocus() && !reducedMotion && last>0 && lineWidth>0)offset=(offset+((now-last).coerceAtMost(60)/1000f)*speed*resources.displayMetrics.density)%(lineWidth+gap)
        last=now
        val y=(height-paint.ascent()-paint.descent())/2
        canvas.save();canvas.clipRect(12f,0f,width-12f,height.toFloat());canvas.drawText(line,16f-offset,y,paint)
        if(!reducedMotion && lineWidth>0)canvas.drawText(line,16f-offset+lineWidth+gap,y,paint)
        canvas.restore()
        if(hasFocus()){paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;canvas.drawRect(1f,1f,width-1f,height-1f,paint);paint.style=Paint.Style.FILL}
        if(running && !held && !hasFocus() && !reducedMotion && isShown)postInvalidateOnAnimation()
    }
    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:android.graphics.Rect?){super.onFocusChanged(gainFocus,direction,previouslyFocusedRect);last=0;invalidate()}
    override fun onDetachedFromWindow(){stop();super.onDetachedFromWindow()}
}
