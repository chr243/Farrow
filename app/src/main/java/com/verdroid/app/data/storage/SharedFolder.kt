package com.verdroid.app.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The user-visible shared folder `/storage/emulated/0/Documents/Farrow` with `Input/` (files the user hands to Farrow)
 * and `Output/` (Farrow's deliverables). Needs "All files access" (MANAGE_EXTERNAL_STORAGE) on Android 11+.
 * [hasAccess] is injectable so the folder logic is testable on a temp dir.
 */
class SharedFolder(val root: File, private val access: () -> Boolean) {

    fun hasAccess(): Boolean = runCatching(access).getOrDefault(false)

    val input: File get() = File(root, INPUT)
    val output: File get() = File(root, OUTPUT)

    /** Whether Documents/Farrow, Input/ and Output/ all exist. */
    fun exists(): Boolean = root.isDirectory && input.isDirectory && output.isDirectory

    /** Creates Documents/Farrow, Input/ and Output/ when missing. Returns true when all three exist afterwards. */
    fun ensure(): Boolean {
        if (!hasAccess()) return false
        listOf(root, input, output).forEach { if (!it.isDirectory) it.mkdirs() }
        return exists()
    }

    companion object {
        const val INPUT = "Input"
        const val OUTPUT = "Output"
        /** Path as the user sees it (primary external storage). */
        const val DISPLAY_PATH = "/storage/emulated/0/Documents/Farrow"

        fun android(): SharedFolder = SharedFolder(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Farrow"),
        ) { Environment.isExternalStorageManager() }

        /** Opens the system "All files access" screen for Farrow (falls back to the global list). */
        fun accessIntent(context: Context): Intent {
            val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
            return if (own.resolveActivity(context.packageManager) != null) own
            else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}
