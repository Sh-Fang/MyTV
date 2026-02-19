package com.mytv.compatible

data class Channel(val id: Int, val name: String, val fileCount: Int)
data class Position(val fileIndex: Int, val offset: Double)
data class ScheduleItem(val title: String, val startsAt: String, val endsAt: String, val current: Boolean)
