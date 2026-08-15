package io.colens.mcp.designer

import com.inductiveautomation.ignition.designer.model.DesignerContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Tools-menu entry that shows the ready-to-paste command for connecting a client to this
 * Designer. The port is OS-assigned and the secret rotates per session, so showing the live
 * command here removes any need for a separate discovery CLI.
 */
class ConnectDialog(
    private val context: DesignerContext,
    private val endpoint: () -> Endpoint?,
) {

    data class Endpoint(val host: String, val port: Int, val secret: String, val discoveryFile: String)

    // Note: the listener must not call a method named `show()` — inside `apply` on a JMenuItem
    // that resolves to the deprecated Component.show() instead of ours.
    val menuItem: JMenuItem = JMenuItem("MCP Connection Info…").apply {
        addActionListener { showDialog() }
    }

    /**
     * The name the client registers this endpoint under, suffixed with the project so a second
     * Designer's command adds a server rather than overwriting the first one's.
     *
     * Two Designers on the *same* project still collide. The dialog prints the discovery-file path,
     * which carries the PID, so that is the tie-break — a PID in the server name would change on
     * every restart and leave the client full of dead entries.
     */
    private fun clientName(): String {
        val slug = context.projectName
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?.takeIf { it.isNotEmpty() }
        return if (slug == null) BASE_CLIENT_NAME else "$BASE_CLIENT_NAME-$slug"
    }

    private fun showDialog() {
        val current = endpoint()
        if (current == null) {
            JOptionPane.showMessageDialog(
                context.frame,
                "The MCP endpoint is not running. Check the Designer console for errors.",
                "Ignition MCP",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }

        val command = buildString {
            append("claude mcp add --transport http ${clientName()} ")
            append("http://${current.host}:${current.port}/mcp ")
            append("--header \"Authorization: Bearer ${current.secret}\"")
        }

        val text = JTextArea(command).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            rows = 4
        }

        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            preferredSize = Dimension(560, 240)

            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JLabel("<html><b>Project:</b> ${context.projectName}</html>"))
                    add(JLabel("<html><b>Endpoint:</b> http://${current.host}:${current.port}/mcp</html>"))
                    add(JLabel("<html><b>Discovery file:</b> ${current.discoveryFile}</html>"))
                },
                BorderLayout.NORTH,
            )
            add(JScrollPane(text), BorderLayout.CENTER)
            add(
                JButton("Copy command").apply {
                    addActionListener {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(command), null)
                    }
                },
                BorderLayout.SOUTH,
            )
        }

        JOptionPane.showMessageDialog(
            context.frame,
            panel,
            "Ignition MCP — Designer connection",
            JOptionPane.PLAIN_MESSAGE,
        )
    }

    private companion object {
        const val BASE_CLIENT_NAME = "ignition-designer"
    }
}
