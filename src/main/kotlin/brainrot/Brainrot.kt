package brainrot

import com.github.timan1802.typingbongocat.BongoCatController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.io.BufferedInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

object Brainrot {
    private const val KEY = "brainrot.enabled"

    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(KEY, value, true)
}

class ToggleBrainrot : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent) = Brainrot.enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        Brainrot.enabled = state
        if (state) Fonts.enforce() else Fonts.restore()
    }
}

class TypingHook : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (Brainrot.enabled) {
            BongoCatController.getInstance().update(editor)
            Effects.typed(editor)
        }
        return Result.CONTINUE
    }
}

class ActionHook : AnActionListener {
    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
        if (!Brainrot.enabled) return
        when (ActionManager.getInstance().getId(action)) {
            "SaveAll" -> Boom.play()
            "EditorEnter" -> event.getData(CommonDataKeys.EDITOR)?.let {
                BongoCatController.getInstance().flip(it)
                Effects.typed(it)
            }
        }
    }
}

object Boom {
    private const val STREAK_WINDOW_MS = 2000
    private const val MAX_STREAK = 8
    private const val PITCH_STEP = 0.15f

    private val sound by lazy {
        AudioSystem.getAudioInputStream(BufferedInputStream(Boom::class.java.getResourceAsStream("/brainrot/boom.wav")!!))
            .use { it.format to it.readAllBytes() }
    }
    private var streak = 0
    private var lastPlayed = 0L

    fun play() {
        val now = System.currentTimeMillis()
        streak = if (now - lastPlayed < STREAK_WINDOW_MS) minOf(streak + 1, MAX_STREAK) else 0
        lastPlayed = now
        val (format, bytes) = sound
        val pitched = AudioFormat(format.sampleRate * (1 + PITCH_STEP * streak), 16, 1, true, false)
        // Not every mixer accepts an odd sample rate, so fall back to the unpitched format.
        for (candidate in listOf(pitched, format)) {
            val played = runCatching {
                AudioSystem.getClip().apply {
                    addLineListener { if (it.type == LineEvent.Type.STOP) close() }
                    open(candidate, bytes, 0, bytes.size)
                    start()
                }
            }
            if (played.isSuccess) return
        }
    }
}
