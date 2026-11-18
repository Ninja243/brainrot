package brainrot

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AppEditorFontOptions
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import java.awt.Font
import java.awt.GraphicsEnvironment

class FontEnforcer : AppLifecycleListener, EditorColorsListener {
    override fun appFrameCreated(commandLineArgs: List<String>) = Fonts.enforce()

    override fun globalSchemeChange(scheme: EditorColorsScheme?) = Fonts.enforce()
}

object Fonts {
    private const val FAMILY = "Comic Mono"
    private const val PREVIOUS_KEY = "brainrot.previousFont"

    private var applying = false

    private val available by lazy {
        runCatching {
            for (name in listOf("ComicMono.ttf", "ComicMono-Bold.ttf")) {
                Fonts::class.java.getResourceAsStream("/brainrot/$name")!!.use {
                    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, it))
                }
            }
        }.onFailure { thisLogger().warn("brainrot: could not register $FAMILY, editor font left alone", it) }
        // registerFont returns false when the family is already installed system-wide, so ask for the font instead.
        Font(FAMILY, Font.PLAIN, 12).family == FAMILY
    }

    fun enforce() {
        if (applying || !Brainrot.enabled || !available) return
        val current = AppEditorFontOptions.getInstance().fontPreferences.fontFamily
        if (current == FAMILY) return
        PropertiesComponent.getInstance().setValue(PREVIOUS_KEY, current)
        setFamily(FAMILY)
    }

    fun restore() {
        PropertiesComponent.getInstance().getValue(PREVIOUS_KEY)?.let(::setFamily)
    }

    private fun setFamily(family: String) {
        applying = true
        try {
            val options = AppEditorFontOptions.getInstance()
            val preferences = FontPreferencesImpl()
            options.fontPreferences.copyTo(preferences)
            val size = preferences.getSize2D(preferences.fontFamily)
            preferences.clearFonts()
            preferences.register(family, size)
            options.update(preferences)
            EditorFactory.getInstance().refreshAllEditors()
        } finally {
            applying = false
        }
    }
}
