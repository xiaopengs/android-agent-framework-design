package com.openminis.app.data.repository

// RESEARCHER-5 PROBE TEST — written for the JVM verification bench, NOT part of
// the repository's test suite. Exercises MemoryRepository.kt (copied verbatim)
// against a temp directory to confirm the recall/truncation/revoke semantics
// documented in /data/kb/parts/05-context-memory.md §7.
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryRepositoryProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo() = MemoryRepository(tmp.newFolder("memory"))

    private fun dateStr(daysAgo: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - daysAgo * 86400_000L))

    @Test
    fun `AND keywords match within a merged plus-minus-2-line window`() {
        val dir = tmp.newFolder("m1")
        val r = MemoryRepository(dir)
        File(dir, "2026-01-02.md").writeText(
            "alpha setup\nfiller one\nbeta target\nfiller two\nfiller three\n",
        )
        val out = r.getMemory("alpha beta", "daily")
        assertTrue(out.contains("alpha setup"))
        assertTrue(out.contains("beta target"))
        assertTrue(out.contains("filler one")) // context line between hits
        assertTrue(out.contains("1 match(es)")) // ranges merged into one
    }

    @Test
    fun `keywords farther apart than the window do not match`() {
        val dir = tmp.newFolder("m2")
        val r = MemoryRepository(dir)
        val lines = MutableList(12) { "filler$it" }
        lines[0] = "alpha"; lines[11] = "beta"
        File(dir, "2026-01-02.md").writeText(lines.joinToString("\n"))
        assertTrue(r.getMemory("alpha beta", "daily").startsWith("No matches found"))
    }

    @Test
    fun `matching is case-insensitive and Chinese works by substring`() {
        val dir = tmp.newFolder("m3")
        val r = MemoryRepository(dir)
        File(dir, "2026-01-02.md").writeText("API_KEY rotation\n记忆召回算法验证\n")
        assertTrue(r.getMemory("api_key", "daily").contains("API_KEY rotation"))
        assertTrue(r.getMemory("召回", "daily").contains("记忆召回算法验证"))
    }

    @Test
    fun `keyword search is capped at 60 lines with a truncation note`() {
        val dir = tmp.newFolder("m4")
        val r = MemoryRepository(dir)
        File(dir, "2026-01-02.md").writeText((0 until 100).joinToString("\n") { "hit line$it" })
        val out = r.getMemory("hit", "daily")
        assertTrue(out.contains("[Output truncated at 60 lines]"))
        assertTrue(out.contains("hit line59"))
        assertFalse(out.contains("hit line60"))
    }

    @Test
    fun `full dump is capped at 500 lines`() {
        val dir = tmp.newFolder("m5")
        val r = MemoryRepository(dir)
        File(dir, "2026-01-02.md").writeText((0 until 600).joinToString("\n") { "row$it" })
        val out = r.getMemory("", "daily")
        assertTrue(out.contains("(showing first 500 of 600 lines)"))
        assertTrue(out.contains("[Output truncated at 500 lines]"))
        assertFalse(out.contains("row500"))
    }

    @Test
    fun `30KB byte cap stops after the oversized entry and reports the cap`() {
        val dir = tmp.newFolder("m6")
        val r = MemoryRepository(dir)
        val huge = "hit " + "x".repeat(40 * 1024)
        File(dir, "2026-01-03.md").writeText(huge)
        File(dir, "2026-01-02.md").writeText("hit second file")
        val out = r.getMemory("hit", "daily")
        assertTrue(out.contains("[Truncated at 30KB byte cap"))
        assertTrue(out.contains("2026-01-03.md")) // newest first, kept whole
        assertFalse(out.contains("second file")) // gate fires before next file
    }

    @Test
    fun `scope all puts GLOBAL_md first and daily-only excludes it`() {
        val dir = tmp.newFolder("m7")
        val r = MemoryRepository(dir)
        File(dir, "GLOBAL.md").writeText("global needle fact")
        File(dir, "2026-01-02.md").writeText("daily needle fact")
        val all = r.getMemory("needle", "all")
        assertTrue(all.indexOf("GLOBAL.md") < all.indexOf("2026-01-02.md"))
        assertFalse(r.getMemory("needle", "daily").contains("GLOBAL.md"))
    }

    @Test
    fun `writeMemory prepends timestamped entries and revokeEntry removes todays exact body`() {
        val dir = tmp.newFolder("m8")
        val r = MemoryRepository(dir)
        r.writeMemory("first entry")
        r.writeMemory("second entry")
        val today = File(dir, "${dateStr(0)}.md")
        val text = today.readText()
        assertTrue(text.indexOf("second entry") < text.indexOf("first entry"))
        assertTrue(text.startsWith("<!-- "))
        val result = r.revokeEntry("second entry")
        assertTrue(result is MemoryRepository.EntryMutationResult.Success)
        assertFalse(today.readText().contains("second entry"))
        assertTrue(today.readText().contains("first entry"))
    }

    @Test
    fun `revokeEntry refuses entries older than yesterday`() {
        val dir = tmp.newFolder("m9")
        val r = MemoryRepository(dir)
        val old = File(dir, "${dateStr(5)}.md")
        old.writeText("<!-- 2026-01-01 10:00:00 -->\nold固化 entry\n\n")
        val before = old.readText()
        assertEquals(MemoryRepository.EntryMutationResult.NotFound, r.revokeEntry("old固化 entry"))
        assertEquals(before, old.readText())
    }

    @Test
    fun `daily injection takes at most 3 files within 30 days at 200 lines each`() {
        val dir = tmp.newFolder("m10")
        val r = MemoryRepository(dir)
        for (d in 0..3) {
            File(dir, "${dateStr(d)}.md").writeText((0 until 250).joinToString("\n") { "d${d}row$it" })
        }
        File(dir, "${dateStr(40)}.md").writeText("ancient row")
        val frag = r.loadRecentDailyMemoryFragment()!!
        assertTrue(frag.contains("Today's daily log"))
        assertTrue(frag.contains("Yesterday's daily log"))
        assertTrue(frag.contains("... (50 more lines, use memory_get to search)"))
        assertTrue(frag.contains("d2row0"))
        assertFalse(frag.contains("d3row0")) // 4th file excluded
        assertFalse(frag.contains("ancient row")) // outside 30-day window
        assertFalse(frag.contains("d0row200")) // 200-line cap
    }

    @Test
    fun `global fragment is full-text with anti-revival header and null when empty`() {
        val dir = tmp.newFolder("m11")
        val r = MemoryRepository(dir)
        assertEquals(null, r.loadGlobalMemoryFragment())
        File(dir, "GLOBAL.md").writeText("user preference line")
        val frag = r.loadGlobalMemoryFragment()!!
        assertTrue(frag.contains("read-only, user-maintained"))
        assertTrue(frag.contains("defer to the user's latest message"))
        assertTrue(frag.endsWith("user preference line"))
    }
}