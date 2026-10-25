package com.commercialoutcomes.uktelevision

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Uses already loaded metadata: remote focus never starts a request or a player. */
@Composable
fun VodFocusDetails(title: VodTitle?, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF101F2E)).padding(12.dp)) {
        Text(title?.title ?: "Highlight a film or series", color=Color.White, fontSize=17.sp,
            fontWeight=FontWeight.Bold, maxLines=2, overflow=TextOverflow.Ellipsis)
        if(title != null) {
            Text((listOfNotNull(title.year?.toString(), if(title.kind=="show")"TV series" else "Film")+title.genres.take(3)).joinToString(" · "),
                color=Color(0xFF64E7CB), fontSize=11.sp, maxLines=2, overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(title?.description?.takeIf { it.isNotBlank() } ?: if(title==null)
            "Its story and programme details will appear here." else "No synopsis supplied by this provider.",
            color=Color(0xFFCFDAE3), fontSize=13.sp, lineHeight=18.sp, maxLines=12,
            overflow=TextOverflow.Ellipsis, modifier=Modifier.weight(1f,fill=false))
    }
}
