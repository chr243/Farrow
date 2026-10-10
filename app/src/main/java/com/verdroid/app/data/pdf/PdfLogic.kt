package com.verdroid.app.data.pdf

/**
 * Pure helpers for the pdf_* tools (JVM-testable, no Android): page-range specs, output names and the
 * summary-friendly chunking of extracted text with page markers.
 */
object PdfPages {
    private val TOKEN = Regex("""^(\d+|last|end)(?:\s*-\s*(\d+|last|end)?)?$""")
    const val MAX_TOKENS = 100

    /**
     * Validates a 1-based, inclusive page spec ("1-3,5,7-", "last", "all") and returns its canonical form
     * (lowercase, no spaces; "all" for blank). Throws [IllegalArgumentException] with a model-readable message.
     */
    fun normalize(spec: String?): String {
        val s = spec?.trim()?.lowercase()?.replace(" ", "").orEmpty()
        if (s.isEmpty() || s == "all") return "all"
        val tokens = s.split(',').filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "pages is empty" }
        require(tokens.size <= MAX_TOKENS) { "pages has too many parts (max $MAX_TOKENS)" }
        tokens.forEach { t ->
            val m = TOKEN.matchEntire(t) ?: throw IllegalArgumentException("Bad page range '$t' (use e.g. 1-3,5,7- or last)")
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.all(Char::isDigit)) require(a.toInt() >= 1) { "Pages start at 1 ('$t')" }
            if (a.all(Char::isDigit) && b.isNotEmpty() && b.all(Char::isDigit)) {
                require(b.toInt() >= a.toInt()) { "Bad page range '$t' (end before start)" }
            }
        }
        return tokens.joinToString(",")
    }

    /** 1-based pages selected by [spec] in a PDF of [count] pages (order kept; same rules as the Python helper). */
    fun resolve(spec: String?, count: Int): List<Int> {
        val n = normalize(spec)
        if (n == "all") return (1..count).toList()
        fun num(x: String) = if (x == "last" || x == "end") count else x.toInt()
        return n.split(',').flatMap { t ->
            val m = TOKEN.matchEntire(t)!!
            val a = num(m.groupValues[1])
            val hasDash = t.contains('-')
            val b = when {
                !hasDash -> a
                m.groupValues[2].isEmpty() -> count
                else -> num(m.groupValues[2])
            }
            require(a <= count) { "Page $a is past the end (the PDF has $count pages)" }
            require(b >= a) { "Bad page range '$t' (end before start)" }
            (a..minOf(b, count)).toList()
        }
    }

    /** Compresses sorted-or-not 1-based pages into a spec, merging consecutive runs: [1,2,3,5] → "1-3,5". */
    fun compress(pages: List<Int>): String {
        if (pages.isEmpty()) return ""
        val out = mutableListOf<String>()
        var start = pages[0]; var prev = pages[0]
        for (p in pages.drop(1)) {
            if (p == prev + 1) { prev = p; continue }
            out += if (start == prev) "$start" else "$start-$prev"
            start = p; prev = p
        }
        out += if (start == prev) "$start" else "$start-$prev"
        return out.joinToString(",")
    }

    /** The rest of [spec] starting at [nextPage] (for "call again with pages=…"); null when nothing is left. */
    fun remaining(spec: String?, count: Int, nextPage: Int?): String? {
        if (nextPage == null) return null
        val all = resolve(spec, count)
        val i = all.indexOf(nextPage)
        if (i < 0) return null
        return compress(all.drop(i)).ifEmpty { null }
    }
}

object PdfNames {
    private val UNSAFE = Regex("""[^\p{L}\p{N}._+-]+""")

    /** A file-name-safe stem (keeps letters/digits/._+-, max 80 chars). */
    fun safeStem(s: String): String = s.replace(UNSAFE, "_").trim('_', '.').take(80).ifEmpty { "document" }

    /**
     * Normalises an output name the agent chose (or [default]) to a path under Output/ with extension [ext]:
     * "x" → "Output/x.pdf", "Output/sub/x.pdf" stays. Absolute shared-folder paths are allowed only inside Output/.
     * Throws [IllegalArgumentException] for anything that escapes Output/ (checked again by the sandbox).
     */
    fun outputRel(name: String?, default: String, ext: String): String {
        var n = name?.trim()?.replace('\\', '/')?.takeIf { it.isNotEmpty() } ?: default
        val shared = com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH
        if (n.startsWith("$shared/")) n = n.removePrefix("$shared/")
        n = n.removePrefix("./")
        if (n.startsWith("Output/")) n = n.removePrefix("Output/")
        require(!n.startsWith("/") && !n.startsWith("Input/")) { "Output must be a file under Output/" }
        require(n.split('/').none { it == ".." || it == "." }) { "Output name must not contain . or .. segments" }
        require(n.isNotBlank() && !n.endsWith("/")) { "Output needs a file name" }
        if (!n.lowercase().endsWith(".$ext")) n = "$n.$ext"
        return "Output/$n"
    }

    /** Spec → a short name part: "1-3,5" → "p1-3_5". */
    fun pagesTag(spec: String): String = "p" + spec.replace(",", "_").take(40)
}

/** One summary chunk: the pages it covers and its marked text. */
data class PdfChunk(val firstPage: Int, val lastPage: Int, val text: String) {
    val pages: String get() = if (firstPage == lastPage) "$firstPage" else "$firstPage-$lastPage"
}

object PdfText {
    const val MIN_CHUNK = 1_000
    const val MAX_CHUNK = 20_000

    fun marker(page: Int, cont: Boolean = false) = "--- Page $page${if (cont) " (cont.)" else ""} ---"

    /** All pages as one text with `--- Page N ---` markers. */
    fun marked(pages: List<Pair<Int, String>>): String =
        pages.joinToString("\n\n") { (p, t) -> marker(p) + "\n" + t.trim() }.trim()

    /**
     * Packs pages into chunks of at most [chunkChars] characters (markers included) so the model can summarise piece
     * by piece; whole pages are kept together when they fit, longer pages are split on paragraph/line/space boundaries
     * with a "(cont.)" marker.
     */
    fun chunks(pages: List<Pair<Int, String>>, chunkChars: Int): List<PdfChunk> {
        val size = chunkChars.coerceIn(MIN_CHUNK, MAX_CHUNK)
        val out = mutableListOf<PdfChunk>()
        val buf = StringBuilder(); var first = -1; var last = -1
        fun flush() {
            if (buf.isNotEmpty()) out += PdfChunk(first, last, buf.toString().trim())
            buf.setLength(0); first = -1
        }
        fun add(page: Int, block: String) {
            val sep = if (buf.isEmpty()) 0 else 2
            if (buf.isNotEmpty() && buf.length + sep + block.length > size) flush()
            if (buf.isNotEmpty()) buf.append("\n\n")
            if (first < 0) first = page
            last = page
            buf.append(block)
        }
        for ((page, raw) in pages) {
            val text = raw.trim()
            val head = marker(page)
            if (head.length + 1 + text.length <= size) { add(page, head + "\n" + text); continue }
            var rest = text; var cont = false
            while (rest.isNotEmpty()) {
                val h = marker(page, cont)
                val room = size - h.length - 1
                val piece = if (rest.length <= room) rest else rest.substring(0, cut(rest, room))
                add(page, h + "\n" + piece.trim())
                rest = rest.substring(piece.length).trimStart()
                cont = true
            }
        }
        flush()
        return out
    }

    /** Best split index ≤ [max]: last paragraph break, else line break, else space (in the second half), else [max]. */
    internal fun cut(s: String, max: Int): Int {
        val half = max / 2
        for (sep in listOf("\n\n", "\n", " ")) {
            val i = s.lastIndexOf(sep, max - sep.length)
            if (i >= half) return i + sep.length
        }
        return max
    }
}
