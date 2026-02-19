package com.mytv.compatible

import android.app.Activity
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.VideoView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

const val BASE_URL = "http://192.168.2.29:8080"

class MainActivity : Activity() {

    private val client = OkHttpClient()
    private val gson = Gson()
    private var wsCall: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val channels = mutableListOf<Channel>()
    private var currentChIdx = 0
    private var chInfoJob: Job? = null

    private lateinit var videoView: VideoView
    private lateinit var tvChannelInfo: TextView
    private lateinit var schedulePanel: LinearLayout
    private lateinit var tvScheduleChannelName: TextView
    private lateinit var lvSchedule: ListView
    private lateinit var scheduleAdapter: ScheduleAdapter
    private lateinit var noConnectionView: View
    private lateinit var tvNoConnectionDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.videoView)
        tvChannelInfo = findViewById(R.id.tvChannelInfo)
        schedulePanel = findViewById(R.id.schedulePanel)
        tvScheduleChannelName = findViewById(R.id.tvScheduleChannelName)
        lvSchedule = findViewById(R.id.lvSchedule)

        scheduleAdapter = ScheduleAdapter(this)
        lvSchedule.adapter = scheduleAdapter

        noConnectionView = findViewById(R.id.noConnectionView)
        tvNoConnectionDetail = findViewById(R.id.tvNoConnectionDetail)

        hideSystemUI()

        // 播完自动播下一个
        videoView.setOnCompletionListener {
            val ch = channels.getOrNull(currentChIdx) ?: return@setOnCompletionListener
            scope.launch { playNextFile(ch) }
        }

        scope.launch {
            loadChannels()
            connectWS()
        }
    }

    private fun hideSystemUI() {
        window.decorView.post {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }
    }

    private suspend fun loadChannels() {
        val body = get("/api/channels")
        if (body == null) {
            showNoConnection("无法连接到 $BASE_URL")
            return
        }
        val type = object : TypeToken<List<Channel>>() {}.type
        channels.clear()
        channels.addAll(gson.fromJson(body, type))
        hideNoConnection()
    }

    private suspend fun playChannel(chIdx: Int) {
        val ch = channels.getOrNull(chIdx) ?: return
        currentChIdx = chIdx
        hideSchedule()
        flashChInfo(ch.name)

        val body = get("/api/position/${ch.id}") ?: return
        val pos: Position = gson.fromJson(body, Position::class.java)
        playFile(ch.id, pos.fileIndex, pos.offset)
    }

    // 记录当前文件索引，用于播完后递增
    private var currentFileIdx = 0

    private fun playFile(chId: Int, fileIdx: Int, offset: Double) {
        currentFileIdx = fileIdx
        val url = "$BASE_URL/api/video/$chId/$fileIdx"
        videoView.setVideoURI(Uri.parse(url))
        videoView.setOnPreparedListener { mp ->
            if (offset > 0) mp.seekTo((offset * 1000).toInt())
            videoView.start()
        }
        videoView.requestFocus()
    }

    private suspend fun playNextFile(ch: Channel) {
        currentFileIdx = (currentFileIdx + 1) % ch.fileCount
        playFile(ch.id, currentFileIdx, 0.0)
    }

    private fun flashChInfo(name: String) {
        tvChannelInfo.text = name
        tvChannelInfo.alpha = 0f
        tvChannelInfo.visibility = View.VISIBLE
        tvChannelInfo.animate().alpha(1f).setDuration(200).start()
        chInfoJob?.cancel()
        chInfoJob = scope.launch {
            delay(3000)
            tvChannelInfo.animate().alpha(0f).setDuration(200).withEndAction {
                tvChannelInfo.visibility = View.GONE
            }.start()
        }
    }

    private fun showSchedule(chName: String, items: List<ScheduleItem>) {
        tvScheduleChannelName.text = chName
        scheduleAdapter.submitList(items)
        schedulePanel.visibility = View.VISIBLE
        schedulePanel.translationY = schedulePanel.height.toFloat()
        schedulePanel.animate().translationY(0f).setDuration(300).start()
    }

    private fun hideSchedule() {
        if (schedulePanel.visibility != View.VISIBLE) return
        schedulePanel.animate().translationY(schedulePanel.height.toFloat()).setDuration(300)
            .withEndAction { schedulePanel.visibility = View.GONE }.start()
    }

    private fun connectWS() {
        val request = Request.Builder().url("ws://10.0.2.2:8080/api/ws").build()
        wsCall = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (channels.isEmpty()) return@launch
                    when {
                        text.startsWith("SWITCH:") -> {
                            val idx = text.removePrefix("SWITCH:").toIntOrNull() ?: return@launch
                            playChannel(idx)
                        }
                        text == "VOL_UP" -> {
                            val am = getSystemService(AUDIO_SERVICE) as AudioManager
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        }
                        text == "VOL_DOWN" -> {
                            val am = getSystemService(AUDIO_SERVICE) as AudioManager
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        }
                        text == "TOGGLE_INFO" -> toggleSchedule()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    showNoConnection("连接已断开，正在重试...")
                    delay(2000)
                    // 重试时也重新拉取频道列表
                    loadChannels()
                    connectWS()
                }
            }
        })
    }

    private suspend fun toggleSchedule() {
        if (schedulePanel.visibility == View.VISIBLE) {
            hideSchedule()
            return
        }
        val ch = channels.getOrNull(currentChIdx) ?: return
        val body = get("/api/schedule/${ch.id}") ?: return
        val type = object : TypeToken<List<ScheduleItem>>() {}.type
        val items: List<ScheduleItem> = gson.fromJson(body, type)
        showSchedule(ch.name, items)
    }

    private fun showNoConnection(detail: String) {
        tvNoConnectionDetail.text = detail
        noConnectionView.visibility = View.VISIBLE
    }

    private fun hideNoConnection() {
        noConnectionView.visibility = View.GONE
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL$path").build()
            client.newCall(req).execute().use { it.body?.string() }
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        wsCall?.cancel()
        videoView.stopPlayback()
        scope.cancel()
    }
}
