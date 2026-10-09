package com.farrow.app.data.termux

/** Steps of the one-tap "Set up Termux" flow in Settings > Tools, in order. */
enum class TermuxSetupStep { INSTALL, GRANT, ALLOW_EXTERNAL, STORAGE, DONE }

/**
 * Decides the next Termux setup step from what the app can observe, and what to tell the user.
 * - INSTALL: open F-Droid (cannot be automated).
 * - GRANT: runtime request for com.termux.permission.RUN_COMMAND (automated dialog).
 * - ALLOW_EXTERNAL: Termux refuses RUN_COMMAND until allow-external-apps is set, so the command is copied to the
 *   clipboard and Termux is opened for a single paste.
 * - STORAGE: `termux-setup-storage` is started in a Termux session via RUN_COMMAND; the user taps Allow.
 */
object TermuxSetupFlow {
    fun next(installed: Boolean, permission: Boolean, answering: Boolean?, storage: Boolean?): TermuxSetupStep = when {
        !installed -> TermuxSetupStep.INSTALL
        !permission -> TermuxSetupStep.GRANT
        answering != true -> TermuxSetupStep.ALLOW_EXTERNAL
        storage != true -> TermuxSetupStep.STORAGE
        else -> TermuxSetupStep.DONE
    }

    /** Message shown while a step is in progress; [retry] = the same step is still pending after the user came back. */
    fun message(step: TermuxSetupStep, retry: Boolean): String = when (step) {
        TermuxSetupStep.INSTALL -> (if (retry) "Termux is still not installed. " else "") +
            "Install Termux from F-Droid (not the Play Store build), open it once, then come back — setup continues automatically."
        TermuxSetupStep.GRANT -> if (retry) "The Run commands in Termux permission was not granted. Tap Set up Termux to ask again " +
            "(or allow it in Android Settings > Apps > Farrow > Permissions)." else "Allow Farrow to run commands in Termux."
        TermuxSetupStep.ALLOW_EXTERNAL -> (if (retry) "Termux still doesn't answer. " else "") +
            "The allow-external-apps command is copied. In Termux, long-press the screen → Paste, press Enter, wait for OK, then come back to Farrow."
        TermuxSetupStep.STORAGE -> (if (retry) "Termux still can't write shared storage. Tap Set up Termux to try again, or type " +
            "termux-setup-storage in Termux. " else "") + "Termux opens a storage prompt — tap Allow, then come back to Farrow."
        TermuxSetupStep.DONE -> "Termux is set up ✅"
    }
}
