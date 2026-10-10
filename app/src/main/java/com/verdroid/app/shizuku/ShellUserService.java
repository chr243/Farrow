package com.verdroid.app.shizuku;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService, started by Shizuku with app_process in its own process (uid shell). Deliberately plain Java
 * with ZERO app dependencies: no Kotlin stdlib, no Hilt/Application, no androidx, no org.json, no static init — so
 * nothing can crash the process before it hands its binder back (the user's logcat showed "Service record … is not
 * started in 30000 ms" with no :shell process). Kept by proguard-rules.pro.
 */
public class ShellUserService extends IShellService.Stub {
    private static final int MAX = 200_000;

    public ShellUserService() {
        try { android.util.Log.i("ShellUserService", "created uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid()); } catch (Throwable ignored) {}
    }

    /** Shizuku >= 13 prefers a (Context) constructor when present. */
    public ShellUserService(android.content.Context context) { this(); }

    @Override public void destroy() { System.exit(0); }

    @Override public void exit() { destroy(); }

    @Override public String exec(String command, String workDir, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            if (workDir != null && !workDir.isEmpty()) {
                File d = new File(workDir);
                if (d.isDirectory()) pb.directory(d);
            }
            Process p = pb.start();
            StringBuilder out = new StringBuilder(), err = new StringBuilder();
            Thread tOut = pump(p.getInputStream(), out), tErr = pump(p.getErrorStream(), err);
            long t = Math.max(1_000L, Math.min(timeoutMs, 600_000L));
            boolean finished = p.waitFor(t, TimeUnit.MILLISECONDS);
            if (!finished) p.destroyForcibly();
            tOut.join(2_000); tErr.join(2_000);
            return "{\"exit_code\":" + (finished ? p.exitValue() : -1) + ",\"stdout\":" + q(out) + ",\"stderr\":" + q(err) +
                ",\"timed_out\":" + (!finished) + "}";
        } catch (Throwable e) {
            return "{\"error\":" + q(e.getClass().getSimpleName() + ": " + e.getMessage()) + "}";
        }
    }

    private static Thread pump(InputStream in, StringBuilder sb) {
        Thread th = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) { synchronized (sb) { if (sb.length() < MAX) sb.append(line).append('\n'); } }
            } catch (Throwable ignored) {}
        });
        th.start();
        return th;
    }

    private static String q(CharSequence s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
