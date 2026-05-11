package com.pixeldweller.instantpear.overlay

import androidx.compose.runtime.mutableStateOf
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.pixeldweller.instantpear.network.PearClient
import com.pixeldweller.instantpear.protocol.PearMessage
import com.pixeldweller.instantpear.settings.PearSettings
import java.awt.Color
import java.net.URLEncoder
import java.util.UUID

/**
 * Orchestrates an OS-level screen-annotation session without any embedded
 * browser:
 *   1. Opens a WebSocket to the lobby relay, creates a UUID lobby.
 *   2. Launches the user's system default browser pointed at the host page.
 *      The page runs in Firefox (the target environment) which handles
 *      getDisplayMedia + WebRTC natively on a secure HTTPS context.
 *   3. Plugin stays in the lobby as a silent observer — it receives all
 *      annotation messages and capture metadata over the same WS and draws
 *      them on a JNA click-through overlay window.
 */
@Service(Service.Level.PROJECT)
class ScreenshareService(private val project: Project) {
    private val log = Logger.getInstance(ScreenshareService::class.java)

    val running = mutableStateOf(false)
    val lobbyCode = mutableStateOf("")
    val inviteLink = mutableStateOf("")
    val statusMessage = mutableStateOf("")

    private var overlay: OverlayWindow? = null
    private var client: PearClient? = null
    private var myUserId: String? = null

    fun start(userName: String, keyOverride: String?) {
        if (running.value) return

        val settings = PearSettings.getInstance().state
        val wsUrl = settings.serverUrl.ifBlank { "ws://localhost:9274/ws" }
        // Browser navigates over HTTPS for secure-context getDisplayMedia.
        // Plugin stays on plain WS (no self-signed cert hassle).
        val browserBase = if (settings.screenshareHttps)
            wsToHttpsBase(wsUrl, settings.screenshareHttpsPort)
        else
            wsToHttpBase(wsUrl)
        val useSockJs = settings.useSockJS
        // Plugin-side transport: when SockJS is chosen (e.g. strict networks
        // that block raw WS) point PearClient at the HTTP /sockjs endpoint.
        val pluginWsUrl = if (useSockJs) {
            val httpBase = wsToHttpBase(wsUrl)
            "$httpBase/sockjs"
        } else wsUrl

        val uuid = UUID.randomUUID().toString()
        val key = keyOverride?.takeIf { it.isNotBlank() } ?: ""
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val keyQuery = if (key.isNotEmpty()) "&key=${enc(key)}" else ""
        val sockjsQuery = if (useSockJs) "&sockjs=1" else ""
        val turnQuery = buildString {
            if (settings.turnEnabled && settings.turnUrl.isNotBlank()) {
                append("&turnUrl=").append(enc(settings.turnUrl))
                if (settings.turnUsername.isNotBlank()) append("&turnUser=").append(enc(settings.turnUsername))
                if (settings.turnPassword.isNotBlank()) append("&turnPass=").append(enc(settings.turnPassword))
            }
        }
        val hostUrl = "$browserBase/host/$uuid?name=${enc(userName)}$keyQuery$sockjsQuery$turnQuery"
        val invite = buildString {
            append("$browserBase/lobby/$uuid")
            val params = buildList {
                if (key.isNotEmpty()) add("key=${enc(key)}")
                if (useSockJs) add("sockjs=1")
                if (settings.turnEnabled && settings.turnUrl.isNotBlank()) {
                    add("turnUrl=${enc(settings.turnUrl)}")
                    if (settings.turnUsername.isNotBlank()) add("turnUser=${enc(settings.turnUsername)}")
                    if (settings.turnPassword.isNotBlank()) add("turnPass=${enc(settings.turnPassword)}")
                }
            }
            if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
        }

        lobbyCode.value = uuid
        inviteLink.value = invite
        statusMessage.value = "Creating lobby..."

        overlay = OverlayWindow(
            onNoteMoved = { id, nx, ny, text ->
                client?.send(
                    PearMessage(
                        type = PearMessage.OVERLAY_NOTE,
                        noteId = id,
                        nx = nx, ny = ny,
                        text = text,
                        userId = myUserId,
                        userName = "Host-Overlay",
                    )
                )
            },
            onNoteDeleted = { id ->
                client?.send(
                    PearMessage(
                        type = PearMessage.OVERLAY_NOTE_DELETE,
                        noteId = id,
                        userId = myUserId,
                    )
                )
            },
        )

        val pc = PearClient(
            serverUrl = pluginWsUrl,
            onMessage = { msg -> ApplicationManager.getApplication().invokeLater { handleMessage(msg) } },
            onConnected = {
                // Plugin is an observer — sets observer=true so the browser
                // host won't try to open a WebRTC peer with it.
                client?.send(
                    PearMessage(
                        type = PearMessage.CREATE_LOBBY,
                        lobbyCode = uuid,
                        lobbyKey = key.ifEmpty { null },
                        userName = "Host-Overlay",
                        observer = true,
                    )
                )
            },
            onDisconnected = { reason ->
                ApplicationManager.getApplication().invokeLater {
                    statusMessage.value = "Disconnected: $reason"
                    stopInternal()
                }
            },
            onError = { err ->
                ApplicationManager.getApplication().invokeLater {
                    log.warn("Screenshare WS error", err)
                    statusMessage.value = "WS error: ${err.message}"
                    stopInternal()
                }
            },
            useSockJS = useSockJs,
        )
        client = pc
        pc.connect()

        statusMessage.value = "Opening browser — accept the HTTPS cert once, then pick a display..."
        // Give the lobby a moment to be created before the browser joins.
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(500)
            ApplicationManager.getApplication().invokeLater {
                BrowserUtil.browse(hostUrl)
                running.value = true
            }
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        val sendLeave = client != null && running.value
        try {
            if (sendLeave) client?.send(PearMessage(type = PearMessage.LEAVE_LOBBY))
        } catch (_: Throwable) {}
        try { client?.disconnect() } catch (_: Throwable) {}
        client = null
        overlay?.hide()
        overlay = null
        myUserId = null
        running.value = false
        lobbyCode.value = ""
        inviteLink.value = ""
        statusMessage.value = "Screenshare stopped"
    }

    // ── incoming ─────────────────────────────────────────────────────────
    private fun handleMessage(msg: PearMessage) {
        when (msg.type) {
            PearMessage.LOBBY_CREATED -> {
                myUserId = msg.userId
                statusMessage.value = "Lobby live — waiting for host browser to join..."
            }
            PearMessage.USER_JOINED -> {
                val name = msg.userName?.takeIf { it.isNotBlank() } ?: "Guest"
                if (!isOwnAvatar(name)) {
                    overlay?.addBanner("$name joined", OverlayWindow.BannerKind.JOIN)
                }
            }
            PearMessage.USER_LEFT -> {
                val uid = msg.userId
                if (uid != null) overlay?.removeCursor(uid)
                val name = msg.userName?.takeIf { it.isNotBlank() } ?: "Guest"
                if (!isOwnAvatar(name)) {
                    overlay?.addBanner("$name left", OverlayWindow.BannerKind.LEAVE)
                }
            }
            PearMessage.CAPTURE_INFO -> {
                val w = msg.captureWidth ?: return
                val h = msg.captureHeight ?: return
                val bounds = OverlayWindow.findMonitorBoundsForCapture(w, h)
                overlay?.show(bounds)
                statusMessage.value = "Overlay active on ${bounds.width}x${bounds.height} @ (${bounds.x},${bounds.y})"
            }
            PearMessage.OVERLAY_CURSOR -> {
                val ov = overlay ?: return
                val nx = msg.nx ?: return
                val ny = msg.ny ?: return
                ov.updateCursor(
                    userId = msg.userId ?: "anon",
                    name = msg.userName ?: "Guest",
                    color = parseColor(msg.color),
                    nx = nx, ny = ny,
                )
            }
            PearMessage.OVERLAY_CLICK -> {
                val nx = msg.nx ?: return
                val ny = msg.ny ?: return
                overlay?.addClick(nx, ny, parseColor(msg.color))
            }
            PearMessage.OVERLAY_HINT -> {
                val nx = msg.nx ?: return
                val ny = msg.ny ?: return
                overlay?.addHint(nx, ny, msg.text ?: "")
            }
            PearMessage.OVERLAY_NOTE -> {
                val nx = msg.nx ?: return
                val ny = msg.ny ?: return
                val id = msg.noteId ?: return
                overlay?.upsertNote(id, nx, ny, msg.text ?: "")
            }
            PearMessage.OVERLAY_NOTE_DELETE -> {
                val id = msg.noteId ?: return
                overlay?.removeNote(id)
            }
            PearMessage.ERROR -> {
                statusMessage.value = "Server error: ${msg.message}"
            }
        }
    }

    /**
     * The plugin connects as `Host-Overlay`; the host browser tab connects
     * with the user-configured name. Suppress banners for both so the host
     * doesn't see a "you joined" or "you left" notification about their own
     * presence in the lobby.
     */
    private fun isOwnAvatar(name: String): Boolean {
        if (name == "Host-Overlay") return true
        val configured = PearSettings.getInstance().state.userName.trim()
        return configured.isNotEmpty() && name == configured
    }

    // ── URL helpers ──────────────────────────────────────────────────────
    private fun parseColor(s: String?): Color {
        if (s.isNullOrBlank()) return Color(59, 130, 246)
        return try {
            if (s.startsWith("#")) Color.decode(s) else Color.decode("#$s")
        } catch (_: Exception) {
            Color(59, 130, 246)
        }
    }

    private fun wsToHttpBase(wsUrl: String): String {
        val trimmed = wsUrl.trimEnd('/')
        return when {
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://").removeSuffix("/ws")
            trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://").removeSuffix("/ws")
            trimmed.startsWith("https://") -> trimmed.removeSuffix("/ws")
            trimmed.startsWith("http://") -> trimmed.removeSuffix("/ws")
            else -> "http://$trimmed".removeSuffix("/ws")
        }
    }

    private fun wsToHttpsBase(wsUrl: String, httpsPort: Int): String {
        val httpBase = wsToHttpBase(wsUrl)
        val withoutScheme = httpBase.substringAfter("://")
        val host = withoutScheme.substringBefore(':').substringBefore('/')
        return "https://$host:$httpsPort"
    }

    companion object {
        fun getInstance(project: Project): ScreenshareService = project.service()
    }
}
