package io.github.kmphub.idea

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.kmphub.core.FileChange
import io.github.kmphub.core.ProjectFiles
import java.io.File

/** Reads project files through the IDE, so unsaved editor changes are taken into account. Call inside a read action. */
class IdeProjectFiles(project: Project) : ProjectFiles {
    private val base = project.basePath

    override fun read(path: String): String? {
        val root = base ?: return null
        val file = LocalFileSystem.getInstance().findFileByPath("$root/$path") ?: return null
        if (file.isDirectory) return null
        return FileDocumentManager.getInstance().getDocument(file)?.text ?: VfsUtilCore.loadText(file)
    }
}

object ChangeWriter {
    /** Applies all changes as one undoable command and saves the documents so Gradle sees them. */
    fun apply(project: Project, changes: List<FileChange>) {
        val root = project.basePath ?: return
        val fs = LocalFileSystem.getInstance()
        val existing = changes.associateWith { fs.refreshAndFindFileByIoFile(File(root, it.path)) }
        WriteCommandAction.runWriteCommandAction(project, "Add KMP Dependencies", null, {
            val documents = FileDocumentManager.getInstance()
            for ((change, found) in existing) {
                val file = found ?: run {
                    val target = File(root, change.path)
                    VfsUtil.createDirectoryIfMissing(target.parent)?.createChildData(this, target.name)
                } ?: continue
                val document = documents.getDocument(file) ?: continue
                document.setText(StringUtil.convertLineSeparators(change.after))
                documents.saveDocument(document)
            }
        })
    }
}

internal fun notify(project: Project, text: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance().getNotificationGroup("KMP Dependency Hub").createNotification(text, type).notify(project)
}

/**
 * Application.runReadAction(Computable) is stable from 2025.1 through 2026.x, unlike ReadAction.compute
 * (deprecated in 2026.x) and ReadAction.computeBlocking (missing before 2026.x).
 */
internal fun <T> readAction(block: () -> T): T =
    ApplicationManager.getApplication().runReadAction(Computable { block() })
