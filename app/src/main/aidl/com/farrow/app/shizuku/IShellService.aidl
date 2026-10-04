// Runs inside Shizuku's process (shell or root uid) as a Shizuku UserService.
package com.farrow.app.shizuku;

interface IShellService {
    // Reserved by Shizuku: called when the service is being destroyed.
    void destroy() = 16777114;

    void exit() = 1;

    // Runs `sh -c command` in workDir (may be empty) and returns JSON {"exit_code","stdout","stderr","timed_out"}.
    String exec(String command, String workDir, long timeoutMs) = 2;
}
