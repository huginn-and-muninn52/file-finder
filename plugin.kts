import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.LogicalPosition
import liveplugin.*
import java.nio.file.Paths

registerAction(
    id = "SwitchEnRu",
    keyStroke = "ctrl alt BACK_SLASH"
) { event: AnActionEvent ->
    val project = event.project ?: run {
        show("No project found")
        return@registerAction
    }

    // Helper: check if a file is already open in any editor
    fun isFileOpen(file: VirtualFile): Boolean {
        return FileEditorManager.getInstance(project).getEditors(file).isNotEmpty()
    }

    // Helper: get folder prefix (en/ru) for a file
    fun getFolderPrefix(file: VirtualFile): String {
        val path = file.path
        return when {
            path.contains("/en/") -> "en"
            path.contains("/ru/") -> "ru"
            else -> ""
        }
    }

    // Get the list of selected files (or a single file)
    val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
    var filesToProcess = emptyList<VirtualFile>()

    if (selectedFiles != null && selectedFiles.isNotEmpty()) {
        filesToProcess = selectedFiles.filter { !it.isDirectory }
        if (filesToProcess.isEmpty()) {
            show("Selected items are folders. Please select files.")
            return@registerAction
        }
    } else {
        val editorFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        if (editorFile != null && !editorFile.isDirectory) {
            filesToProcess = listOf(editorFile)
        } else {
            show("No file selected or opened. Please select files in Project view or open a file in editor.")
            return@registerAction
        }
    }

    // Store for each file whether it was open before processing and its scroll offset
    val sourceWasOpenMap = filesToProcess.associateWith { isFileOpen(it) }
    val sourceScrollOffsets = mutableMapOf<VirtualFile, Int>()

    var totalOpened = 0
    val targetFileNames = mutableListOf<String>()
    val errors = mutableListOf<String>()

    for (currentFile in filesToProcess) {
        val wasSourceOpen = sourceWasOpenMap[currentFile] ?: false

        // Get the current editor for the source file (if open) to capture caret and scroll
        val currentEditors = FileEditorManager.getInstance(project).getEditors(currentFile)
        val currentTextEditor = currentEditors.filterIsInstance<TextEditor>().firstOrNull()
        val caretLine = currentTextEditor?.editor?.caretModel?.logicalPosition?.line
        val scrollOffset = currentTextEditor?.editor?.scrollingModel?.verticalScrollOffset

        // Remember scroll offset for later use (even if the file is already open)
        if (scrollOffset != null) {
            sourceScrollOffsets[currentFile] = scrollOffset
        }

        // Open/activate the source file
        FileEditorManager.getInstance(project).openFile(currentFile, true)
        if (!wasSourceOpen) {
            totalOpened++  // source file was not open, count it as new
        }

        // Find counterpart in the opposite language folder
        val currentPath = Paths.get(currentFile.path)
        var parent = currentPath.parent
        var found = false
        var targetPath = currentPath

        while (parent != null && !found) {
            val folderName = parent.fileName?.toString()
            when (folderName) {
                "en" -> {
                    val newParent = parent.parent.resolve("ru")
                    targetPath = newParent.resolve(parent.relativize(currentPath))
                    found = true
                }
                "ru" -> {
                    val newParent = parent.parent.resolve("en")
                    targetPath = newParent.resolve(parent.relativize(currentPath))
                    found = true
                }
            }
            parent = parent.parent
        }

        if (!found) {
            errors.add("No 'en' or 'ru' folder found for ${currentFile.name}")
            continue
        }

        val targetFile = LocalFileSystem.getInstance().findFileByPath(targetPath.toString())
        if (targetFile != null && !targetFile.isDirectory) {
            val wasTargetOpen = isFileOpen(targetFile)
            // Open/activate the counterpart
            FileEditorManager.getInstance(project).openFile(targetFile, true)
            if (!wasTargetOpen) {
                totalOpened++  // target file was not open, count it as new
            }

            // Store target file name for notifications
            val prefix = getFolderPrefix(targetFile)
            targetFileNames.add(if (prefix.isNotEmpty()) "$prefix/${targetFile.name}" else targetFile.name)

            // Apply the preserved caret line and scroll offset to the target file
            val targetEditors = FileEditorManager.getInstance(project).getEditors(targetFile)
            val targetTextEditor = targetEditors.filterIsInstance<TextEditor>().firstOrNull()
            targetTextEditor?.let { editor ->
                // 1. Move caret to the saved line (if any)
                if (caretLine != null) {
                    editor.editor.caretModel.moveToLogicalPosition(LogicalPosition(caretLine, 0))
                }
                // 2. Restore vertical scroll offset with a short delay to ensure editor is fully rendered
                val savedOffset = sourceScrollOffsets[currentFile]
                if (savedOffset != null) {
                    ApplicationManager.getApplication().invokeLater(
                        { editor.editor.scrollingModel.scrollVertically(savedOffset) },
                        ModalityState.NON_MODAL
                    )
                }
            }
        } else {
            errors.add("Corresponding file not found for ${currentFile.name}")
        }
    }

    // Build notification message
    val msg = buildString {
        when {
            filesToProcess.size > 1 -> {
                // Multiple files: show count of new files
                append("Opened $totalOpened files")
                if (errors.isNotEmpty()) {
                    append("\nErrors:\n")
                    append(errors.joinToString("\n"))
                }
            }
            filesToProcess.size == 1 -> {
                val currentFile = filesToProcess[0]
                val wasSourceOpen = sourceWasOpenMap[currentFile] ?: false
                if (wasSourceOpen) {
                    // Source was already open: show only the target file name
                    if (targetFileNames.isNotEmpty()) {
                        append("Opened: ${targetFileNames[0]}")
                    } else {
                        append("No target file opened")
                    }
                    if (errors.isNotEmpty()) {
                        append("\nErrors:\n")
                        append(errors.joinToString("\n"))
                    }
                } else {
                    // Source was not open: show count
                    append("Opened $totalOpened files")
                    if (errors.isNotEmpty()) {
                        append("\nErrors:\n")
                        append(errors.joinToString("\n"))
                    }
                }
            }
            else -> {
                append("No files were processed.")
            }
        }
    }
    show(msg)
}

// show("Plugin loaded! Switch en↔ru with Ctrl+Alt+\\")