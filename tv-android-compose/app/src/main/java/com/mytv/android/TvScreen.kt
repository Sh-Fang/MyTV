package com.mytv.android

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text

@OptIn(UnstableApi::class)
@Composable
fun TvScreen(
    player: ExoPlayer,
    channels: List<Channel>,
    currentChIdx: Int,
    showChInfo: Boolean,
    showSchedule: Boolean,
    schedule: List<ScheduleItem>,
) {
    val currentChName = channels.getOrNull(currentChIdx)?.name ?: ""

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 播放器
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { it.player = player }
        )

        // 频道切换提示（右上角）
        AnimatedVisibility(
            visible = showChInfo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
        ) {
            Text(
                text = currentChName,
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(5.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        // 节目单面板（底部）
        AnimatedVisibility(
            visible = showSchedule,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            SchedulePanel(channelName = currentChName, schedule = schedule)
        }
    }
}

@Composable
fun SchedulePanel(channelName: String, schedule: List<ScheduleItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xD1000000))
            .padding(horizontal = 40.dp, vertical = 24.dp)
    ) {
        Text(
            text = channelName,
            color = Color(0xFFAAAAAA),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        schedule.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 5.dp)
            ) {
                Text(
                    text = if (item.current) "▶" else " ",
                    color = Color(0xFF44AAFF),
                    fontSize = 12.sp,
                    modifier = Modifier.width(16.dp)
                )
                Text(
                    text = "${item.startsAt} – ${item.endsAt}",
                    color = if (item.current) Color.White else Color(0xFFAAAAAA),
                    fontSize = 15.sp,
                    modifier = Modifier.width(110.dp)
                )
                Text(
                    text = item.title,
                    color = if (item.current) Color(0xFF44AAFF) else Color.White,
                    fontSize = if (item.current) 18.sp else 16.sp,
                    fontWeight = if (item.current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
