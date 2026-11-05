package com.commercialoutcomes.uktelevision
import android.content.Context
object PersonalQueue {
 const val ID="personal-channel-queue"
 fun saved(context:Context)=VodSavedCodec.decode(context.getSharedPreferences("vod-library",0).getString("saved","").orEmpty())
 fun add(saved:VodSaved,title:VodTitle):VodSaved {
  require(title.kind !in setOf("show","season"))
  val base=if(saved.lists.any{it.id==ID})saved else saved.copy(lists=saved.lists+VodList(ID,"My Channel queue"))
  return base.setLists(title,base.lists.filter{base.keyFor(title) in it.keys}.map{it.id}.toSet()+ID)
 }
}
