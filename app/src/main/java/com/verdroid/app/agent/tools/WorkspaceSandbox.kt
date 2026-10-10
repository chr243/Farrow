package com.verdroid.app.agent.tools

import java.io.File

/**
 * Confines every tool path to [root] (filesDir/workspace on device). Absolute paths are treated
 * as relative to the workspace root; anything that resolves outside of it (../, symlinks) is rejected.
 */
class WorkspaceSandbox(root: File) {
    val root: File = root.apply { mkdirs() }.canonicalFile

    @Throws(SecurityException::class)
    fun resolve(path: String?): File {
        val p = (path ?: ".").trim().ifEmpty { "." }
        if (p.contains('\u0000')) throw SecurityException("Invalid path")
        val relative = p.replace('\\', '/').trimStart('/').ifEmpty { "." }
        val candidate = File(root, relative).canonicalFile
        if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Path escapes the workspace: $path")
        }
        return candidate
    }

    fun relativePath(file: File): String =
        file.canonicalFile.relativeTo(root).path.ifEmpty { "." }
}
