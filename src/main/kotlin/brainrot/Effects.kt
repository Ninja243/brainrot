package brainrot

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Key
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min

private const val FRAME_MS = 33
private const val IDLE_MS = 30_000
private const val FULL_RATE = 8.0 // keys per second at which the runner is fully opaque
private const val DECAY = 0.96 // per frame once typing pauses, fades out in about 3 s
private const val TOP = 8
private const val SEGMENT = 8
private const val MAX_TRAIL = 260
private const val BOUNCE_SPEED = 3.0
private const val PAD = 6

private val RAINBOW = listOf(
    Color(0xFF3B30), Color(0xFF9500), Color(0xFFCC00), Color(0x34C759), Color(0x007AFF), Color(0xAF52DE),
)
private val RUNNER = sprite("runner.png")
private val BOUNCER = sprite("bouncer.png")
private val LANE_HEIGHT = TOP + RUNNER.height + 4
private val OVERLAY = Key.create<Overlay>("brainrot.overlay")

private fun sprite(name: String): BufferedImage = ImageIO.read(Overlay::class.java.getResource("/brainrot/$name"))

object Effects {
    fun typed(editor: Editor) {
        val overlay = editor.getUserData(OVERLAY) ?: Overlay(editor).also {
            editor.putUserData(OVERLAY, it)
            EditorUtil.disposeWithEditor(editor, it)
        }
        overlay.typed()
    }
}

private class Overlay(private val editor: Editor) : JComponent(), Disposable {
    private val host = editor.contentComponent
    private var rate = 0.0
    private var lastKey = 0L
    private var runnerX = 0.0

    private var bounceColor: Color? = null
    private var bx = 0.0
    private var by = 0.0
    private var dx = BOUNCE_SPEED
    private var dy = BOUNCE_SPEED
    private var flash = 0

    private val idle = Timer(IDLE_MS) { startBounce() }.apply { isRepeats = false }
    private val frame = Timer(FRAME_MS) { tick() }
    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = syncBounds()
        override fun componentResized(e: ComponentEvent) = syncBounds()
    }

    init {
        host.add(this)
        host.addComponentListener(follow)
    }

    fun typed() {
        val now = System.currentTimeMillis()
        if (lastKey != 0L) rate = 0.8 * rate + 0.2 * (1000.0 / max(now - lastKey, 16L))
        lastKey = now
        if (bounceColor != null) {
            bounceColor = null
            repaint()
        }
        idle.restart()
        frame.start()
        syncBounds()
    }

    // Resizing a hidden overlay on every scroll would repaint the whole editor for nothing.
    private fun syncBounds() {
        if (frame.isRunning) bounds = editor.scrollingModel.visibleArea
    }

    private fun startBounce() {
        if (!Brainrot.enabled || width <= BOUNCER.width || height <= BOUNCER.height) return
        bx = Math.random() * (width - BOUNCER.width)
        by = Math.random() * (height - BOUNCER.height)
        bounceColor = randomColor()
        frame.start()
        syncBounds()
    }

    private fun tick() {
        if (!Brainrot.enabled) {
            rate = 0.0
            bounceColor = null
        }
        if (System.currentTimeMillis() - lastKey > 300) rate *= DECAY
        runnerX = (runnerX + 2 + rate * 1.5) % (width + RUNNER.width + MAX_TRAIL)
        repaint(0, 0, width, LANE_HEIGHT)
        if (bounceColor != null) {
            repaintBouncer()
            stepBounce()
            repaintBouncer()
        }
        if (rate < 0.05 && bounceColor == null) {
            rate = 0.0
            frame.stop()
            repaint()
        }
    }

    private fun stepBounce() {
        val hitX = bx + dx < 0 || bx + dx > width - BOUNCER.width
        val hitY = by + dy < 0 || by + dy > height - BOUNCER.height
        if (hitX) dx = -dx
        if (hitY) dy = -dy
        bx += dx
        by += dy
        if (hitX || hitY) bounceColor = randomColor()
        if (hitX && hitY) flash = 20
        if (flash > 0) flash--
    }

    private fun repaintBouncer() =
        repaint(bx.toInt() - PAD - 1, by.toInt() - PAD - 1, BOUNCER.width + 2 * PAD + 2, BOUNCER.height + 2 * PAD + 2)

    private fun randomColor() = Color.getHSBColor(Math.random().toFloat(), 0.9f, 0.9f)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            paintRunner(g2)
            paintBouncer(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun paintRunner(g: Graphics2D) {
        val alpha = min(1.0, rate / FULL_RATE).toFloat()
        if (alpha < 0.01f) return
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        val catX = runnerX.toInt() - RUNNER.width
        val trail = min(MAX_TRAIL, (40 + rate * 20).toInt())
        val stripe = RUNNER.height / RAINBOW.size
        var x = catX - SEGMENT
        var segment = (runnerX / SEGMENT).toInt()
        while (x > catX - trail) {
            val wobble = if (segment % 2 == 0) 0 else 2
            RAINBOW.forEachIndexed { i, color ->
                g.color = color
                g.fillRect(x, TOP + wobble + i * stripe, SEGMENT, stripe)
            }
            x -= SEGMENT
            segment++
        }
        g.drawImage(RUNNER, catX, TOP, null)
    }

    private fun paintBouncer(g: Graphics2D) {
        val color = bounceColor ?: return
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f)
        g.color = if (flash > 0) randomColor() else color
        g.fillRoundRect(bx.toInt() - PAD, by.toInt() - PAD, BOUNCER.width + 2 * PAD, BOUNCER.height + 2 * PAD, 12, 12)
        g.drawImage(BOUNCER, bx.toInt(), by.toInt(), null)
    }

    override fun dispose() {
        idle.stop()
        frame.stop()
        host.removeComponentListener(follow)
        host.remove(this)
    }
}
