package io.github.kmphub.idea

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.kmphub.core.Doctor
import io.github.kmphub.core.Finding
import io.github.kmphub.core.LibraryCatalog
import io.github.kmphub.core.ProjectScanner
import io.github.kmphub.core.Severity

class DoctorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        runDoctor(e.project ?: return)
    }

    companion object {
        fun runDoctor(project: Project) {
            object : Task.Backgroundable(project, "KMP Hub: checking dependencies", false) {
                override fun run(indicator: ProgressIndicator) {
                    val findings = readAction {
                        val files = IdeProjectFiles(project)
                        Doctor(HubData.snapshot.catalog).check(ProjectScanner.scan(files), files)
                    }
                    ApplicationManager.getApplication().invokeLater({ show(project, findings) }, project.disposed)
                }
            }.queue()
        }

        private fun show(project: Project, findings: List<Finding>) {
            if (findings.isEmpty()) {
                notify(project, "KMP Hub Doctor: no problems found.")
                return
            }
            val errors = findings.count { it.severity == Severity.ERROR }
            val text = findings.joinToString("\n") { it.toString() }
            if (errors > 0) {
                Messages.showErrorDialog(project, text, "KMP Hub Doctor: $errors Error(s)")
            } else {
                Messages.showInfoMessage(project, text, "KMP Hub Doctor")
            }
        }
    }
}
