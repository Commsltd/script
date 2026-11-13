package com.commercialoutcomes.uktelevision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray

/** Small bundled land outline. No map SDK, network tiles, WebView or animation loop. */
class PersonalLocationMap(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val land=Path()
    private var latitude:Double?=null
    private var longitude:Double?=null
    private var caption="POSITION CONNECTING"
    private val scale=resources.displayMetrics.density
    init {
        val polygons=JSONArray(context.assets.open("my-channel/land.json").bufferedReader().use{it.readText()})
        for(i in 0 until polygons.length()){
            val ring=polygons.getJSONArray(i)
            for(j in 0 until ring.length()){
                val point=ring.getJSONArray(j);val x=(point.getDouble(0)+180).toFloat();val y=(90-point.getDouble(1)).toFloat()
                if(j==0)land.moveTo(x,y)else land.lineTo(x,y)
            }
            land.close()
        }
        setLayerType(LAYER_TYPE_NONE,null)
    }
    fun position(lat:Double?,lon:Double?,label:String){latitude=lat?.takeIf{it in -90.0..90.0};longitude=lon?.takeIf{it in -180.0..180.0};caption=label;contentDescription=label;invalidate()}
    override fun onDraw(c:Canvas){
        super.onDraw(c);c.drawColor(Color.rgb(7,18,28))
        val pad=8*scale;val mapWidth=width-2*pad;val mapHeight=minOf((height-30*scale).coerceAtLeast(1f),mapWidth/2);val top=5*scale
        paint.color=Color.rgb(28,44,54);paint.strokeWidth=scale;paint.style=Paint.Style.STROKE
        for(lon in -120..120 step 60){val x=pad+(lon+180)/360f*mapWidth;c.drawLine(x,top,x,top+mapHeight,paint)}
        for(lat in -60..60 step 30){val y=top+(90-lat)/180f*mapHeight;c.drawLine(pad,y,pad+mapWidth,y,paint)}
        c.save();c.translate(pad,top);c.scale(mapWidth/360,mapHeight/180);paint.style=Paint.Style.FILL;paint.color=Color.rgb(67,87,98);c.drawPath(land,paint);c.restore()
        val lat=latitude;val lon=longitude
        if(lat!=null && lon!=null){
            val x=pad+((lon+180)/360*mapWidth).toFloat();val y=top+((90-lat)/180*mapHeight).toFloat()
            paint.color=PersonalStyle.mint;paint.style=Paint.Style.STROKE;paint.strokeWidth=scale;c.drawCircle(x,y,7*scale,paint);paint.style=Paint.Style.FILL;c.drawCircle(x,y,3*scale,paint)
            c.drawLine(x-11*scale,y,x+11*scale,y,paint);c.drawLine(x,y-11*scale,x,y+11*scale,paint)
        }
        paint.style=Paint.Style.FILL;paint.color=PersonalStyle.mint;paint.textSize=10*resources.displayMetrics.scaledDensity
        c.drawText(caption.take(45),pad,height-7*scale,paint)
    }
}
