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
    val background=Color.rgb(5,8,12);val panel=Color.rgb(13,18,24);val foreground=Color.rgb(236,244,246);val muted=Color.rgb(156,180,193);val mint=Color.rgb(255,145,45)
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density).toInt()
    fun focusBackground(c:Context):StateListDrawable {
        fun shape(focused:Boolean)=GradientDrawable().apply{setColor(if(focused) Color.rgb(52,39,23) else panel);cornerRadius=0f;setStroke(dp(c,if(focused)2 else 1),if(focused)mint else Color.rgb(31,40,49))}
        return StateListDrawable().apply{addState(intArrayOf(android.R.attr.state_focused),shape(true));addState(intArrayOf(android.R.attr.state_pressed),shape(true));addState(intArrayOf(),shape(false))}
    }
    fun label(c:Context,size:Float,color:Int=foreground)=TextView(c).apply{textSize=size;setTextColor(color);includeFontPadding=false}
    fun action(c:Context,text:String,click:()->Unit)=label(c,16f).apply{this.text=text;gravity=Gravity.CENTER;isFocusable=true;isClickable=true;background=focusBackground(c);setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));setOnClickListener{click()}}
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
    init {isFocusable=true;isClickable=true;setBackgroundColor(Color.rgb(14,18,23))}
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
