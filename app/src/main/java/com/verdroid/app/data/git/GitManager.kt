package com.verdroid.app.data.git

import com.verdroid.app.agent.tools.WorkspaceSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class GitStatusInfo(
    val branch: String?,
    val clean: Boolean,
    val added: Set<String>,
    val changed: Set<String>,
    val modified: Set<String>,
    val removed: Set<String>,
    val missing: Set<String>,
    val untracked: Set<String>,
    val conflicting: Set<String>,
)

/** JGit 5.13 (Java 8 API level, works on Android 10+) operating only inside the agent workspace. */
@Singleton
class GitManager @Inject constructor(
    private val sandbox: WorkspaceSandbox,
    private val creds: GitCredentialStore,
) {
    private fun credentials() = creds.token?.let { UsernamePasswordCredentialsProvider(creds.username, it) }

    fun repoDir(path: String?): File = sandbox.resolve(path?.ifBlank { "." } ?: ".")

    private fun open(path: String?): Git {
        val dir = repoDir(path)
        if (!File(dir, ".git").exists()) throw IllegalArgumentException("Not a git repository: ${sandbox.relativePath(dir)}")
        return Git.open(dir)
    }

    suspend fun clone(url: String, path: String?, branch: String?, depth: Int?): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Only https:// remotes are supported" }
        val name = path?.ifBlank { null } ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        val dir = sandbox.resolve(name)
        if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) throw IllegalArgumentException("Target is not empty: ${sandbox.relativePath(dir)}")
        val cmd = Git.cloneRepository().setURI(url).setDirectory(dir).setCredentialsProvider(credentials())
        if (!branch.isNullOrBlank()) cmd.setBranch(branch)
        // depth > 0 → shallow clone isn't supported by JGit 5.x; the parameter is accepted but ignored.
        cmd.call().use { git -> git.repository.branch }
        sandbox.relativePath(dir)
    }

    suspend fun status(path: String?): GitStatusInfo = withContext(Dispatchers.IO) {
        open(path).use { git ->
            val s = git.status().call()
            GitStatusInfo(git.repository.branch, s.isClean, s.added, s.changed, s.modified, s.removed, s.missing, s.untracked, s.conflicting)
        }
    }

    /** Stages everything (adds + removals) and commits. Returns the new commit id. */
    suspend fun commit(path: String?, message: String): String = withContext(Dispatchers.IO) {
        open(path).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val ident = PersonIdent(creds.authorName, creds.authorEmail)
            git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call().id.name
        }
    }

    suspend fun push(path: String?, remote: String?, branch: String?): List<String> = withContext(Dispatchers.IO) {
        val cp = credentials() ?: throw IllegalStateException("No Git token set (Settings > Shizuku & Git setup)")
        open(path).use { git ->
            val cmd = git.push().setRemote(remote?.ifBlank { null } ?: "origin").setCredentialsProvider(cp)
            if (!branch.isNullOrBlank()) cmd.add(branch)
            cmd.call().flatMap { result -> result.remoteUpdates.map { "${it.remoteName}: ${it.status}${it.message?.let { m -> " ($m)" } ?: ""}" } }
        }
    }
}
