package com.farrow.app.data.update

import kotlinx.serialization.json.*

/** A GitHub release as far as the updater cares. */
data class ReleaseInfo(val tag: String, val version: String, val name: String, val notes: String, val htmlUrl: String?, val asset: ApkAsset?)

data class ApkAsset(val name: String, val url: String, val size: Long)

/** Pure update logic (unit-tested): version comparison, asset choice, release parsing, auto-check throttle. */
object UpdateLogic {
    const val LATEST_URL = "https://api.github.com/repos/chr243/Farrow/releases/latest"
    const val AUTO_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private data class V(val nums: List<Int>, val pre: String?)

    private fun parse(v: String): V? {
        val s = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = s.substringBefore('-')
        val pre = s.substringAfter('-', "").ifEmpty { null }
        val nums = core.split('.').map { it.toIntOrNull() ?: return null }
        return if (nums.isEmpty()) null else V(nums, pre)
    }

    /** Numeric semver compare ("1.0.10" > "1.0.9", "v1.2" == "1.2.0", "1.0.5-rc1" < "1.0.5"); null if unparseable. */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until maxOf(x.nums.size, y.nums.size)) {
            val c = (x.nums.getOrElse(i) { 0 }).compareTo(y.nums.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            x.pre == y.pre -> 0
            x.pre == null -> 1
            y.pre == null -> -1
            else -> x.pre.compareTo(y.pre)
        }
    }

    fun isNewer(latest: String, current: String): Boolean = (compare(latest, current) ?: 0) > 0

    /**
     * The APK to install: `Farrow-*.apk` of the installed build type (debug → `-debug.apk`), else any `Farrow-*.apk`,
     * else any `.apk`. Assets with size 0 or no URL are skipped.
     */
    fun pickAsset(assets: List<ApkAsset>, buildType: String = "debug"): ApkAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.url.startsWith("https://") && it.size > 0 }
        val farrow = apks.filter { it.name.startsWith("Farrow-", ignoreCase = true) }
        return farrow.firstOrNull { it.name.endsWith("-$buildType.apk", ignoreCase = true) } ?: farrow.firstOrNull() ?: apks.firstOrNull()
    }

    fun parseRelease(body: String, buildType: String = "debug"): ReleaseInfo? {
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val tag = s("tag_name") ?: return null
        if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            ApkAsset(ao["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                ao["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                ao["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        return ReleaseInfo(tag, tag.removePrefix("v").removePrefix("V"), s("name") ?: tag, s("body").orEmpty(), s("html_url"), pickAsset(assets, buildType))
    }

    fun autoCheckDue(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0 || nowMs - lastCheckMs >= AUTO_CHECK_EVERY_MS || nowMs < lastCheckMs
}
