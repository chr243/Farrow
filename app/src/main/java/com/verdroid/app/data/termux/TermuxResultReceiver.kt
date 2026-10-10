package com.verdroid.app.data.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Result of a Termux RUN_COMMAND execution (bundle keys from Termux's plugin result API). */
data class TermuxResult(
    val tag: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val err: Int?,
    val errmsg: String?,
)

object TermuxResults {
    private val _results = MutableSharedFlow<TermuxResult>(extraBufferCapacity = 32)
    val results: SharedFlow<TermuxResult> = _results.asSharedFlow()
    internal fun emit(r: TermuxResult) { _results.tryEmit(r) }
}

/** Receives the RUN_COMMAND_PENDING_INTENT callback from Termux (only reachable through our PendingIntent). */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val b = intent.getBundleExtra(RESULT_BUNDLE)
        val result = TermuxResult(
            tag = tag,
            stdout = b?.getString("stdout").orEmpty(),
            stderr = b?.getString("stderr").orEmpty(),
            exitCode = b?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
            err = b?.takeIf { it.containsKey("err") }?.getInt("err"),
            errmsg = b?.getString("errmsg"),
        )
        Log.d("TermuxResult", "tag=$tag exit=${result.exitCode} err=${result.err} errmsg=${result.errmsg} stdout=${result.stdout.length}B")
        TermuxResults.emit(result)
    }

    companion object {
        const val ACTION_RESULT = "com.verdroid.app.TERMUX_RESULT"
        const val EXTRA_TAG = "farrow_tag"
        private const val RESULT_BUNDLE = "result"
    }
}
