package io.github.kmphub.idea

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.kmphub.core.InstallResult
import io.github.kmphub.core.Severity
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/** Summarises an install plan and lets the user review the diff before applying it. */
class ApplyChangesDialog(private val project: Project, private val result: InstallResult) : DialogWrapper(project) {

    init {
        title = "KMP Hub: Add Dependencies"
        setOKButtonText(if (result.changes.isEmpty()) "OK" else "Apply ${result.changes.size} File Change(s)")
        isOKActionEnabled = !result.hasErrors
        init()
    }

    override fun createCenterPanel(): JComponent {
        val html = buildString {
            append("<html><body>")
            if (result.changes.isNotEmpty()) {
                append("<b>Files</b><ul>")
                result.changes.forEach { append("<li>").append(StringUtil.escapeXmlEntities(it.path)).append(if (it.before == null) " (new)" else "").append("</li>") }
                append("</ul>")
            }
            append("<b>Details</b><ul>")
            result.messages.forEach { m ->
                val color = when (m.severity) {
                    Severity.ERROR -> "#d9534f"
                    Severity.WARNING -> "#d58512"
                    Severity.INFO -> null
                }
                append("<li>")
                if (color != null) append("<font color='$color'><b>${m.severity.name.lowercase()}:</b></font> ")
                append(StringUtil.escapeXmlEntities(m.text)).append("</li>")
            }
            append("</ul></body></html>")
        }
        val pane = JEditorPane("text/html", html).apply { isEditable = false; border = JBUI.Borders.empty(4) }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JBScrollPane(pane).apply { preferredSize = Dimension(JBUI.scale(620), JBUI.scale(320)) }, BorderLayout.CENTER)
            if (result.changes.isNotEmpty()) add(ActionLink("Show diff…") { showDiff() }, BorderLayout.SOUTH)
        }
    }

    private fun showDiff() {
        val factory = DiffContentFactory.getInstance()
        val requests = result.changes.map { change ->
            val type = FileTypeManager.getInstance().getFileTypeByFileName(change.path)
            SimpleDiffRequest(
                change.path,
                factory.create(project, change.before.orEmpty(), type),
                factory.create(project, change.after, type),
                if (change.before == null) "(new file)" else "Current",
                "After KMP Hub",
            )
        }
        DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.MODAL)
    }
}
