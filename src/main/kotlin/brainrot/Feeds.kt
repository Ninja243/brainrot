package brainrot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.AncestorListenerAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.io.HttpRequests
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.event.KeyEvent
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent

private val FEEDS = mapOf(
    "YouTube Shorts" to "https://www.youtube.com/shorts",
    "TikTok" to "https://www.tiktok.com/foryou",
    "Instagram Reels" to "https://www.instagram.com/reels/",
)

class FeedToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val url = FEEDS[toolWindow.id] ?: return
        val component = if (JBCefApp.isSupported()) feed(url, toolWindow) else JBLabel("Embedded browser is not available in this IDE runtime")
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(component, null, false))
    }

    private fun feed(url: String, toolWindow: ToolWindow): JComponent {
        val browser = JBCefBrowser(url)
        Disposer.register(toolWindow.disposable, browser)
        AdBlock.attach(browser)
        AutoScroll.attach(browser)
        // A hidden tool window keeps its page alive, so audio would play on with the panel closed.
        browser.component.addAncestorListener(object : AncestorListenerAdapter() {
            private var parked = false

            override fun ancestorRemoved(event: AncestorEvent) {
                parked = true
                browser.loadURL("about:blank")
            }

            override fun ancestorAdded(event: AncestorEvent) {
                if (parked) browser.loadURL(url)
                parked = false
            }
        })
        return browser.component
    }
}

private val RULE = Regex("""(@@)?\|\|([a-z0-9.-]+)\^\|?""")

class HostRules(val blocked: Set<String>, val allowed: Set<String>) {
    fun blocks(host: String): Boolean {
        var name = host.lowercase()
        var hit = false
        while (true) {
            if (name in allowed) return false
            if (name in blocked) hit = true
            val dot = name.indexOf('.')
            if (dot < 0) return hit
            name = name.substring(dot + 1)
        }
    }

    companion object {
        // ponytail: plain "||domain^" rules and their "@@" exceptions only; wildcard and $modifier rules (~0.35 % of the list) are skipped
        fun parse(text: String): HostRules {
            val blocked = HashSet<String>()
            val allowed = HashSet<String>()
            for (line in text.lineSequence()) {
                val match = RULE.matchEntire(line.trim()) ?: continue
                (if (match.groupValues[1].isEmpty()) blocked else allowed) += match.groupValues[2]
            }
            return HostRules(blocked, allowed)
        }
    }
}

object AdBlock {
    private const val LIST_URL = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    private val cache: Path = Path.of(PathManager.getSystemPath(), "brainrot", "adguard-dns.txt")
    private val started = AtomicBoolean()

    @Volatile
    private var rules = HostRules(emptySet(), emptySet())

    private val resourceHandler = object : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
            val host = runCatching { URI(request!!.url).host }.getOrNull() ?: return false
            return rules.blocks(host)
        }
    }

    private val requestHandler = object : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler = resourceHandler
    }

    fun attach(browser: JBCefBrowser) {
        // ponytail: list age is checked once per IDE start, not on a timer; add a scheduler if the IDE stays open for weeks
        if (started.compareAndSet(false, true)) ApplicationManager.getApplication().executeOnPooledThread(::refresh)
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
    }

    private fun refresh() {
        val cached = Files.exists(cache)
        if (cached) runCatching { rules = HostRules.parse(Files.readString(cache)) }
        if (cached && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < MAX_AGE_MS) return
        runCatching {
            val text = HttpRequests.request(LIST_URL).readString()
            Files.createDirectories(cache.parent)
            Files.writeString(cache, text)
            rules = HostRules.parse(text)
        }.onFailure { thisLogger().warn("brainrot: ad list download failed, using ${if (cached) "cached copy" else "no blocking"}", it) }
    }
}

object AutoScroll {
    private val script by lazy { AutoScroll::class.java.getResource("/brainrot/autoscroll.js")!!.readText() }

    fun attach(browser: JBCefBrowser) {
        val next = JBCefJSQuery.create(browser as JBCefBrowserBase)
        Disposer.register(browser, next)
        next.addHandler {
            SwingUtilities.invokeLater { pressDown(browser) }
            null
        }
        val js = script.replace("/*NEXT*/", next.inject("''"))
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) cefBrowser?.executeJavaScript(js, frame.url, 0)
            }
        }, browser.cefBrowser)
    }

    // Sites may ignore key events synthesised in page JS (isTrusted = false), so the key goes in through the browser host.
    private fun pressDown(browser: JBCefBrowser) {
        val target = browser.cefBrowser.uiComponent
        for (id in intArrayOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
            target.dispatchEvent(KeyEvent(target, id, System.currentTimeMillis(), 0, KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED))
        }
    }
}
