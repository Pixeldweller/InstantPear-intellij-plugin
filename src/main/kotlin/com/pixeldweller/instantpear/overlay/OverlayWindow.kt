package com.pixeldweller.instantpear.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * OS-level transparent, click-through, always-on-top overlay window.
 * Renders ghost cursors, click ripples, sticky notes and attention hints
 * on top of the screen surface being shared by the host.
 *
 * Sized to match exactly the monitor that was selected in the browser
 * picker so normalized coordinates map 1:1 to physical pixels.
 *
 * The overlay ships in two modes:
 *   - **click-through** (default): mouse events pass straight through to the
 *     underlying applications; ideal while the host is actively using the
 *     shared desktop.
 *   - **interactive**: captures mouse input so the host can drag notes,
 *     copy their contents or remove them. Notes repositioned this way are
 *     broadcast to every lobby participant through [onNoteMoved], and
 *     removals go through [onNoteDeleted].
 */
class OverlayWindow(
    private val onNoteMoved: (id: String, nx: Double, ny: Double, text: String) -> Unit = { _, _, _, _ -> },
    private val onNoteDeleted: (id: String) -> Unit = {},
) {
    private val log = Logger.getInstance(OverlayWindow::class.java)

    data class GhostCursor(
        val userId: String,
        var nx: Double,
        var ny: Double,
        var name: String,
        var color: Color,
        var lastUpdateMs: Long,
    )

    data class Note(
        val id: String,
        var nx: Double,
        var ny: Double,
        var text: String,
    )

    private data class ClickBlip(val nx: Double, val ny: Double, val startMs: Long, val color: Color)
    private data class Hint(val nx: Double, val ny: Double, val text: String, val startMs: Long)

    enum class BannerKind { INFO, JOIN, LEAVE }
    private data class Banner(
        val text: String,
        val kind: BannerKind,
        val startMs: Long,
        val durationMs: Long,
    )

    private data class NoteHit(
        val id: String,
        val bounds: Rectangle,
        val copyBounds: Rectangle,
        val removeBounds: Rectangle,
    )

    private val cursors = ConcurrentHashMap<String, GhostCursor>()
    private val notes = ConcurrentHashMap<String, Note>()
    private val blips = mutableListOf<ClickBlip>()
    private val hints = mutableListOf<Hint>()
    private val banners = mutableListOf<Banner>()

    @Volatile private var interactive: Boolean = false

    private var window: JWindow? = null
    private var panel: OverlayPanel? = null
    private var timer: Timer? = null
    private var hoverTimer: Timer? = null

    /** Shows the overlay over the given screen rectangle (virtual-desktop coords). */
    fun show(bounds: Rectangle) {
        SwingUtilities.invokeLater { showEdt(bounds) }
    }

    fun hide() {
        SwingUtilities.invokeLater {
            timer?.stop(); timer = null
            hoverTimer?.stop(); hoverTimer = null
            window?.isVisible = false
            window?.dispose()
            window = null
            panel = null
            cursors.clear()
            notes.clear()
            synchronized(blips) { blips.clear() }
            synchronized(hints) { hints.clear() }
            synchronized(banners) { banners.clear() }
        }
    }

    fun setInteractive(on: Boolean) {
        interactive = on
        SwingUtilities.invokeLater {
            val w = window ?: return@invokeLater
            applyWin32Style(w, clickThrough = !on)
            panel?.cursor = if (on) Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            else Cursor.getDefaultCursor()
            panel?.repaint()
        }
    }

    fun isInteractive(): Boolean = interactive

    fun updateCursor(userId: String, name: String, color: Color, nx: Double, ny: Double) {
        val now = System.currentTimeMillis()
        cursors.compute(userId) { _, existing ->
            if (existing == null) GhostCursor(userId, nx, ny, name, color, now)
            else existing.apply {
                this.nx = nx; this.ny = ny
                this.name = name; this.color = color
                this.lastUpdateMs = now
            }
        }
    }

    fun removeCursor(userId: String) {
        cursors.remove(userId)
    }

    fun addClick(nx: Double, ny: Double, color: Color = Color(59, 130, 246)) {
        synchronized(blips) { blips.add(ClickBlip(nx, ny, System.currentTimeMillis(), color)) }
    }

    fun addHint(nx: Double, ny: Double, text: String) {
        synchronized(hints) { hints.add(Hint(nx, ny, text, System.currentTimeMillis())) }
    }

    fun addBanner(text: String, kind: BannerKind = BannerKind.INFO, durationMs: Long = 2800) {
        if (text.isBlank()) return
        synchronized(banners) { banners.add(Banner(text, kind, System.currentTimeMillis(), durationMs)) }
    }

    fun upsertNote(id: String, nx: Double, ny: Double, text: String) {
        notes.compute(id) { _, existing ->
            existing?.also { it.nx = nx; it.ny = ny; it.text = text }
                ?: Note(id, nx, ny, text)
        }
    }

    fun removeNote(id: String) { notes.remove(id) }

    // ── internal ─────────────────────────────────────────────────────────
    private fun showEdt(bounds: Rectangle) {
        val w = JWindow()
        w.background = Color(0, 0, 0, 0)
        val p = OverlayPanel()
        w.contentPane = p
        w.bounds = bounds
        w.preferredSize = Dimension(bounds.width, bounds.height)
        w.isAlwaysOnTop = true
        w.focusableWindowState = false
        w.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) { timer?.stop() }
        })
        w.isVisible = true
        applyWin32Style(w, clickThrough = true)

        val animate = Timer(33) { p.repaint() }
        animate.isRepeats = true
        animate.start()

        // Polls the global pointer every 80 ms and auto-flips the overlay
        // between click-through and interactive based on whether the cursor
        // sits on a note's bounds.
        val hover = Timer(80) { pollPointerHover() }
        hover.isRepeats = true
        hover.start()

        window = w
        panel = p
        timer = animate
        hoverTimer = hover
    }

    private fun pollPointerHover() {
        val w = window ?: return
        val p = panel ?: return
        if (p.isDragging()) return
        val info = java.awt.MouseInfo.getPointerInfo() ?: return
        val pt = info.location
        val b = w.bounds
        val localX = pt.x - b.x
        val localY = pt.y - b.y
        val inOverlay = localX in 0 until b.width && localY in 0 until b.height
        val onNote = inOverlay && p.hitTestAnyNote(localX, localY)
        if (onNote && !interactive) {
            setInteractive(true)
        } else if (!onNote && interactive) {
            setInteractive(false)
        }
    }

    private fun applyWin32Style(w: JWindow, clickThrough: Boolean) {
        try {
            val ptr = Native.getComponentPointer(w) ?: run {
                log.warn("OverlayWindow: HWND not available — style unchanged")
                return
            }
            val hwnd = HWND(ptr)
            val existing = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
            var updated = existing or WS_EX_TOOLWINDOW or WS_EX_LAYERED or WS_EX_TOPMOST
            updated = if (clickThrough) {
                updated or WS_EX_TRANSPARENT or WS_EX_NOACTIVATE
            } else {
                updated and WS_EX_TRANSPARENT.inv() and WS_EX_NOACTIVATE.inv()
            }
            User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, updated)
        } catch (e: Throwable) {
            log.warn("OverlayWindow: failed to apply Win32 style", e)
        }
    }

    private inner class OverlayPanel : JPanel() {
        @Volatile private var hits: List<NoteHit> = emptyList()
        private var dragging: String? = null

        fun isDragging(): Boolean = dragging != null

        fun hitTestAnyNote(px: Int, py: Int): Boolean {
            val snapshot = hits
            return snapshot.any { it.bounds.contains(px, py) }
        }

        private var dragOffsetX = 0
        private var dragOffsetY = 0
        private var hoverNote: String? = null

        init {
            isOpaque = false
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { onPress(e) }
                override fun mouseReleased(e: MouseEvent) { onRelease(e) }
                override fun mouseExited(e: MouseEvent) { hoverNote = null; repaint() }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) { onDrag(e) }
                override fun mouseMoved(e: MouseEvent) { onMove(e) }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.composite = AlphaComposite.Clear
            g2.fillRect(0, 0, width, height)
            g2.composite = AlphaComposite.SrcOver
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val now = System.currentTimeMillis()

            if (interactive) {
                // Soft dim so host clearly sees edit mode is on and knows
                // underlying apps are not receiving clicks.
                g2.color = Color(0, 0, 0, 40)
                g2.fillRect(0, 0, width, height)
            }

            drawBlips(g2, now)
            drawHints(g2, now)
            hits = drawNotes(g2)
            drawCursors(g2)
            drawBanners(g2, now)
        }

        private fun drawBlips(g: Graphics2D, now: Long) {
            synchronized(blips) {
                val it = blips.iterator()
                while (it.hasNext()) {
                    val b = it.next()
                    val age = now - b.startMs
                    if (age > 1000) { it.remove(); continue }
                    val (x, y) = toScreen(b.nx, b.ny)
                    val t = age / 1000.0
                    val alpha = (1.0 - t).coerceIn(0.0, 1.0)
                    g.color = Color(b.color.red, b.color.green, b.color.blue, (alpha * 255).toInt())
                    g.stroke = java.awt.BasicStroke(3f)
                    val r = (10 + t * 50).toInt()
                    g.drawOval(x - r, y - r, r * 2, r * 2)
                }
            }
        }

        private fun drawHints(g: Graphics2D, now: Long) {
            synchronized(hints) {
                val it = hints.iterator()
                while (it.hasNext()) {
                    val h = it.next()
                    val age = now - h.startMs
                    if (age > 2500) { it.remove(); continue }
                    val (x, y) = toScreen(h.nx, h.ny)
                    val alpha = (1.0 - age / 2500.0).coerceIn(0.0, 1.0)
                    g.color = Color(250, 204, 21, (alpha * 255).toInt())
                    g.font = g.font.deriveFont(13f)
                    g.drawString(h.text, x + 12, y - 12)
                }
            }
        }

        private fun drawNotes(g: Graphics2D): List<NoteHit> {
            val out = mutableListOf<NoteHit>()
            val showActions = interactive
            val iconSize = 18
            val iconGap = 4
            for (n in notes.values) {
                val (x, y) = toScreen(n.nx, n.ny)
                val fm = g.fontMetrics
                val padX = 10
                val padY = 6
                val textMaxW = 220
                val rawLines = n.text.split('\n')
                val lines = rawLines.map { ellipsize(it, fm, textMaxW) }
                val maxLineW = lines.maxOfOrNull { fm.stringWidth(it) } ?: 0
                val tw = maxLineW.coerceAtMost(textMaxW)
                val lineHeight = fm.height
                val textBlockH = lineHeight * lines.size.coerceAtLeast(1)
                val actionsW = if (showActions) (iconSize * 2 + iconGap + 6) else 0
                val w = tw + padX * 2 + actionsW
                val h = maxOf(textBlockH + padY * 2, iconSize + padY * 2)
                val bx = x - w / 2
                val by = y - h / 2

                val body = Rectangle(bx, by, w, h)
                val isHover = hoverNote == n.id
                val bgAlpha = if (isHover && showActions) 140 else 90
                g.color = Color(250, 204, 21, bgAlpha)
                g.fillRoundRect(bx, by, w, h, 10, 10)
                g.color = Color(250, 204, 21, 220)
                g.drawRoundRect(bx, by, w, h, 10, 10)
                g.color = Color(20, 20, 20)
                for ((i, line) in lines.withIndex()) {
                    g.drawString(line, bx + padX, by + padY + fm.ascent + i * lineHeight)
                }

                var copyRect = Rectangle(0, 0, 0, 0)
                var removeRect = Rectangle(0, 0, 0, 0)
                if (showActions) {
                    val iconsY = by + (h - iconSize) / 2
                    val copyX = bx + w - actionsW - 2
                    val removeX = copyX + iconSize + iconGap
                    copyRect = Rectangle(copyX, iconsY, iconSize, iconSize)
                    removeRect = Rectangle(removeX, iconsY, iconSize, iconSize)

                    drawIcon(g, copyRect, "⧉", Color(30, 30, 30))
                    drawIcon(g, removeRect, "✕", Color(30, 30, 30))
                }
                out.add(NoteHit(n.id, body, copyRect, removeRect))
            }
            return out
        }

        private fun drawIcon(g: Graphics2D, r: Rectangle, glyph: String, fg: Color) {
            g.color = Color(0, 0, 0, 40)
            g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6)
            g.color = fg
            val fm = g.fontMetrics
            val tw = fm.stringWidth(glyph)
            val tx = r.x + (r.width - tw) / 2
            val ty = r.y + (r.height - fm.height) / 2 + fm.ascent
            g.drawString(glyph, tx, ty)
        }

        private fun drawBanners(g: Graphics2D, now: Long) {
            val snapshot = synchronized(banners) {
                banners.removeAll { now - it.startMs > it.durationMs }
                banners.toList()
            }
            if (snapshot.isEmpty()) return
            val gg = g.create() as Graphics2D
            try {
                gg.font = gg.font.deriveFont(14f)
                val fm = gg.fontMetrics
                val padX = 14
                val padY = 8
                val gap = 6
                var y = 18
                for (b in snapshot) {
                    val age = now - b.startMs
                    val remaining = b.durationMs - age
                    val alpha = when {
                        age < 200L -> age / 200.0
                        remaining < 600L -> (remaining.coerceAtLeast(0L) / 600.0)
                        else -> 1.0
                    }.coerceIn(0.0, 1.0)

                    val tw = fm.stringWidth(b.text)
                    val bw = tw + padX * 2
                    val bh = fm.height + padY * 2
                    val bx = width / 2 - bw / 2

                    val (bg, fg) = bannerColors(b.kind)
                    gg.color = Color(bg.red, bg.green, bg.blue, (alpha * 220).toInt())
                    gg.fillRoundRect(bx, y, bw, bh, 14, 14)
                    gg.color = Color(fg.red, fg.green, fg.blue, (alpha * 255).toInt())
                    gg.drawString(b.text, bx + padX, y + padY + fm.ascent)
                    y += bh + gap
                }
            } finally {
                gg.dispose()
            }
        }

        private fun bannerColors(kind: BannerKind): Pair<Color, Color> = when (kind) {
            BannerKind.JOIN  -> Color(34, 197, 94)  to Color.WHITE
            BannerKind.LEAVE -> Color(239, 68, 68)  to Color.WHITE
            BannerKind.INFO  -> Color(59, 130, 246) to Color.WHITE
        }

        private fun drawCursors(g: Graphics2D) {
            val now = System.currentTimeMillis()
            for ((uid, c) in cursors) {
                if (now - c.lastUpdateMs > 15000) {
                    cursors.remove(uid)
                    continue
                }
                val (x, y) = toScreen(c.nx, c.ny)
                val gg = g.create() as Graphics2D
                try {
                    gg.translate(x, y)
                    gg.color = c.color
                    val px = intArrayOf(0, 0, 4, 9, 11, 6, 12)
                    val py = intArrayOf(0, 16, 12, 20, 18, 10, 10)
                    gg.fillPolygon(px, py, px.size)
                    gg.color = Color.BLACK
                    gg.drawPolygon(px, py, px.size)
                    val fm = gg.fontMetrics
                    val labelW = fm.stringWidth(c.name) + 8
                    gg.color = Color(0, 0, 0, 200)
                    gg.fillRect(14, -4, labelW, fm.height)
                    gg.color = Color.WHITE
                    gg.drawString(c.name, 18, 8)
                } finally {
                    gg.dispose()
                }
            }
        }

        private fun toScreen(nx: Double, ny: Double): Pair<Int, Int> {
            return (nx * width).toInt() to (ny * height).toInt()
        }

        private fun fromScreen(px: Int, py: Int): Pair<Double, Double> {
            val nx = (px.toDouble() / width).coerceIn(0.0, 1.0)
            val ny = (py.toDouble() / height).coerceIn(0.0, 1.0)
            return nx to ny
        }

        private fun ellipsize(s: String, fm: java.awt.FontMetrics, maxPx: Int): String {
            if (fm.stringWidth(s) <= maxPx) return s
            val ell = "..."
            var end = s.length
            while (end > 0 && fm.stringWidth(s.substring(0, end) + ell) > maxPx) end--
            return s.substring(0, end) + ell
        }

        // ── input handlers ───────────────────────────────────────────────
        private fun onPress(e: MouseEvent) {
            if (!interactive) return
            val hit = hits.firstOrNull { it.bounds.contains(e.point) } ?: return
            when {
                hit.copyBounds.contains(e.point) -> {
                    val text = notes[hit.id]?.text ?: return
                    copyToClipboard(text)
                }
                hit.removeBounds.contains(e.point) -> {
                    notes.remove(hit.id)
                    onNoteDeleted(hit.id)
                    repaint()
                }
                else -> {
                    dragging = hit.id
                    dragOffsetX = e.x - hit.bounds.x - hit.bounds.width / 2
                    dragOffsetY = e.y - hit.bounds.y - hit.bounds.height / 2
                }
            }
        }

        private fun onDrag(e: MouseEvent) {
            if (!interactive) return
            val id = dragging ?: return
            val n = notes[id] ?: return
            val (nx, ny) = fromScreen(e.x - dragOffsetX, e.y - dragOffsetY)
            n.nx = nx; n.ny = ny
            repaint()
        }

        private fun onRelease(e: MouseEvent) {
            if (!interactive) return
            val id = dragging ?: return
            dragging = null
            val n = notes[id] ?: return
            onNoteMoved(id, n.nx, n.ny, n.text)
        }

        private fun onMove(e: MouseEvent) {
            if (!interactive) { if (hoverNote != null) { hoverNote = null; repaint() }; return }
            val prev = hoverNote
            hoverNote = hits.firstOrNull { it.bounds.contains(e.point) }?.id
            if (prev != hoverNote) repaint()
        }

        private fun copyToClipboard(value: String) {
            try {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(value), null)
            } catch (e: Throwable) {
                log.warn("Clipboard copy failed", e)
            }
        }
    }

    companion object {
        private const val WS_EX_TRANSPARENT = 0x00000020
        private const val WS_EX_LAYERED = 0x00080000
        private const val WS_EX_TOPMOST = 0x00000008
        private const val WS_EX_TOOLWINDOW = 0x00000080
        private const val WS_EX_NOACTIVATE = 0x08000000

        /**
         * Resolves the screen rectangle for the monitor whose native pixel
         * size matches [captureWidth]x[captureHeight]. Falls back to primary.
         */
        fun findMonitorBoundsForCapture(captureWidth: Int, captureHeight: Int): Rectangle {
            val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            val primary = env.defaultScreenDevice
            for (dev in env.screenDevices) {
                val cfg = dev.defaultConfiguration
                val b = cfg.bounds
                val sx = cfg.defaultTransform.scaleX
                val sy = cfg.defaultTransform.scaleY
                val phyW = Math.round(b.width * sx).toInt()
                val phyH = Math.round(b.height * sy).toInt()
                if (phyW == captureWidth && phyH == captureHeight) return b
                if (b.width == captureWidth && b.height == captureHeight) return b
            }
            return primary.defaultConfiguration.bounds
        }
    }
}
