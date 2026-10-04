package com.farrow.app.data.browser

/** Bridge script version helpers (bundled asset vs. the running bridge's /health version). */
object BridgeVersions {
    private val VERSION_RE = Regex("""(?m)^VERSION\s*=\s*["']([0-9][0-9A-Za-z.\-]*)["']""")

    /** `VERSION = "1.8.0"` from the bridge script source. */
    fun parse(script: String): String? = VERSION_RE.find(script)?.groupValues?.get(1)

    /** Compares dotted versions numerically ("1.10.0" > "1.9.2"); non-numeric parts count as 0. */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** True when the running bridge [running] is older than [bundled] (unknown running version = outdated). */
    fun isOutdated(running: String?, bundled: String?): Boolean {
        if (bundled.isNullOrBlank()) return false
        if (running.isNullOrBlank()) return true
        return compare(running, bundled) < 0
    }
}
