package brainrot

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

private const val NYAN_ID = "some.awesome"
private const val VETTED_VERSION = "1.16"

// SHA-256 of the 1.16 jar as served by the JetBrains Marketplace, the build whose bytecode was reviewed.
private const val VETTED_SHA256 = "ac04a24324960494da76cfe08f1952d29d2e4bdf82ee5432f3048f7e7d022ed7"

class NyanPin : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(NYAN_ID)) ?: return@executeOnPooledThread
            val actual = runCatching { sha256(Files.readAllBytes(plugin.pluginPath)) }.getOrNull()
            if (actual == VETTED_SHA256) return@executeOnPooledThread
            Notification(
                "Brainrot",
                "Nyan Progress Bar is not the vetted build",
                "Installed: version ${plugin.version}, SHA-256 ${actual ?: "unreadable"}. " +
                    "Vetted: version $VETTED_VERSION, SHA-256 $VETTED_SHA256.",
                NotificationType.WARNING,
            ).notify(null)
        }
    }
}

fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
