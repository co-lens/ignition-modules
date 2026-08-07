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

    data class Endpoint(val port: Int, val secret: String, val discoveryFile: String)

    // Note: the listener must not call a method named `show()` — inside `apply` on a JMenuItem
    // that resolves to the deprecated Component.show() instead of ours.
    val menuItem: JMenuItem = JMenuItem("MCP Connection Info…").apply {
        addActionListener { showDialog() }
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
            append("claude mcp add --transport http ignition-designer ")
            append("http://127.0.0.1:${current.port}/mcp ")
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
                    add(JLabel("<html><b>Endpoint:</b> http://127.0.0.1:${current.port}/mcp</html>"))
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
}
