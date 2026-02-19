package com.mytv.android

import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

data class Channel(val id: Int, val name: String, val fileCount: Int)
data class Position(val fileIndex: Int, val offset: Double)
data class ScheduleItem(val title: String, val startsAt: String, val endsAt: String, val current: Boolean)

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private val client = OkHttpClient()
    private val gson = Gson()
    private var wsCall: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 动态发现的后端地址
    private var baseUrl: String? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // UI state
    private val channels = mutableStateListOf<Channel>()
    private var currentChIdx by mutableIntStateOf(0)
    private var currentFileIdx by mutableIntStateOf(0)
    private var showChInfo by mutableStateOf(false)
    private var showSchedule by mutableStateOf(false)
    private val schedule = mutableStateListOf<ScheduleItem>()
    private var chInfoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()

        setContent {
            TvScreen(
                player = player,
                channels = channels,
                currentChIdx = currentChIdx,
                showChInfo = showChInfo,
                showSchedule = showSchedule,
                schedule = schedule,
            )
        }

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

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val ch = channels.getOrNull(currentChIdx) ?: return
                    currentFileIdx = (currentFileIdx + 1) % ch.fileCount
                    playFile(ch.id, currentFileIdx, 0.0)
                }
            }
        })

        discoverBackend()
    }

    // mDNS 发现后端
    private fun discoverBackend() {
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                scope.launch { delay(3000); discoverBackend() }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host?.hostAddress ?: return
                        val port = si.port
                        scope.launch {
                            baseUrl = "http://$host:$port"
                            stopDiscovery()
                            loadChannels()
                            connectWS()
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // 后端消失，重新发现
                if (baseUrl != null) {
                    baseUrl = null
                    channels.clear()
                    discoverBackend()
                }
            }
        }

        nsdManager?.discoverServices("_mytv._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
    }

    private suspend fun loadChannels() {
        val body = get("/api/channels") ?: return
        val type = object : TypeToken<List<Channel>>() {}.type
        val list: List<Channel> = gson.fromJson(body, type)
        channels.clear()
        channels.addAll(list)
    }

    private suspend fun playChannel(chIdx: Int) {
        val ch = channels.getOrNull(chIdx) ?: return
        currentChIdx = chIdx
        showSchedule = false
        flashChInfo(ch.name)

        val body = get("/api/position/${ch.id}") ?: return
        val pos: Position = gson.fromJson(body, Position::class.java)
        currentFileIdx = pos.fileIndex
        playFile(ch.id, pos.fileIndex, pos.offset)
    }

    private fun playFile(chId: Int, fileIdx: Int, offset: Double) {
        val url = "${baseUrl}/api/video/$chId/$fileIdx"
        val item = MediaItem.fromUri(url)
        player.setMediaItem(item)
        player.prepare()
        if (offset > 0) player.seekTo((offset * 1000).toLong())
        player.playWhenReady = true
    }

    private fun flashChInfo(name: String) {
        showChInfo = true
        chInfoJob?.cancel()
        chInfoJob = scope.launch {
            delay(3000)
            showChInfo = false
        }
    }

    private fun connectWS() {
        val wsUrl = baseUrl?.replace("http://", "ws://") ?: return
        val request = Request.Builder().url("$wsUrl/api/ws").build()
        wsCall = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (channels.isEmpty()) return@launch
                    if (text.startsWith("SWITCH:")) {
                        val idx = text.removePrefix("SWITCH:").toIntOrNull() ?: return@launch
                        playChannel(idx)
                    } else if (text == "VOL_UP") {
                        val am = getSystemService(AUDIO_SERVICE) as AudioManager
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    } else if (text == "VOL_DOWN") {
                        val am = getSystemService(AUDIO_SERVICE) as AudioManager
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    } else if (text == "TOGGLE_INFO") {
                        toggleSchedule()
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    delay(2000)
                    connectWS()
                }
            }
        })
    }

    private suspend fun toggleSchedule() {
        if (showSchedule) { showSchedule = false; return }
        val ch = channels.getOrNull(currentChIdx) ?: return
        val body = get("/api/schedule/${ch.id}") ?: return
        val type = object : TypeToken<List<ScheduleItem>>() {}.type
        val list: List<ScheduleItem> = gson.fromJson(body, type)
        schedule.clear()
        schedule.addAll(list)
        showSchedule = true
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val url = baseUrl ?: return@withContext null
        try {
            val req = Request.Builder().url("$url$path").build()
            client.newCall(req).execute().use { it.body?.string() }
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        wsCall?.cancel()
        player.release()
        scope.cancel()
    }
}
