package com.verdroid.app.agent.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChartSpecsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun err(s: String) = ChartSpecs.parse(obj(s)).exceptionOrNull()?.message.orEmpty()

    @Test fun `valid bar chart parses`() {
        val spec = ChartSpecs.parse(obj("""{"type":"Bar","title":"Prices","x_label":"Shop","y_label":"EUR",
            "labels":["A","B","C"],"series":[{"name":"Phone","values":[999,1049.5,979]}]}""")).getOrThrow()
        assertEquals("bar", spec.type); assertEquals("Prices", spec.title); assertEquals("Shop", spec.xLabel); assertEquals("EUR", spec.yLabel)
        assertEquals(listOf("A", "B", "C"), spec.labels)
        assertEquals(listOf(999.0, 1049.5, 979.0), spec.series[0].values)
    }

    @Test fun `lenient numbers and stringified arrays`() {
        assertEquals(1299.5, ChartSpecs.number(JsonPrimitive("$1,299.50"))!!, 1e-9)
        assertEquals(12.0, ChartSpecs.number(JsonPrimitive("€ 12"))!!, 1e-9)
        assertEquals(45.0, ChartSpecs.number(JsonPrimitive("45%"))!!, 1e-9)
        assertEquals(-2.0, ChartSpecs.number(JsonPrimitive("-2"))!!, 1e-9)
        assertNull(ChartSpecs.number(JsonPrimitive("n/a")))
        val spec = ChartSpecs.parse(obj("""{"type":"line","labels":"[\"Jan\",\"Feb\"]",
            "series":"[{\"name\":\"x\",\"values\":[\"$1\",\"2\"]}]"}""")).getOrThrow()
        assertEquals(listOf("Jan", "Feb"), spec.labels); assertEquals(listOf(1.0, 2.0), spec.series[0].values)
        val comma = ChartSpecs.parse(obj("""{"type":"bar","labels":"a, b","series":[[3,4]]}""")).getOrThrow()
        assertEquals(listOf("a", "b"), comma.labels); assertEquals(listOf(3.0, 4.0), comma.series[0].values)
    }

    @Test fun `default labels when missing`() {
        val spec = ChartSpecs.parse(obj("""{"type":"bar","series":[{"values":[1,2,3]}]}""")).getOrThrow()
        assertEquals(listOf("1", "2", "3"), spec.labels)
    }

    @Test fun `validation errors are explicit`() {
        assertTrue(err("""{"type":"donut","series":[[1]]}""").contains("type must be"))
        assertTrue(err("""{"type":"bar"}""").contains("series is required"))
        assertTrue(err("""{"type":"bar","labels":["a","b"],"series":[{"values":[1]}]}""").contains("1 values but there are 2 labels"))
        assertTrue(err("""{"type":"bar","series":[{"values":[1,"x"]}]}""").contains("not a number"))
        assertTrue(err("""{"type":"pie","labels":["a","b"],"series":[{"values":[1,2]},{"values":[3,4]}]}""").contains("exactly one series"))
        assertTrue(err("""{"type":"pie","labels":["a","b"],"series":[{"values":[1,-2]}]}""").contains("negative"))
        assertTrue(err("""{"type":"pie","labels":["a","b"],"series":[{"values":[0,0]}]}""").contains("sum to 0"))
        assertTrue(err("""{"type":"scatter","series":[{"values":[1,2]}]}""").contains("scatter needs labels"))
        assertTrue(err("""{"type":"scatter","labels":["1","x"],"series":[{"values":[1,2]}]}""").contains("labels[1]"))
        val many = (1..13).joinToString(",") { "[1]" }
        assertTrue(err("""{"type":"bar","series":[$many]}""").contains("at most"))
        val pts = (1..201).joinToString(",")
        assertTrue(err("""{"type":"line","series":[[$pts]]}""").contains("more than"))
    }

    @Test fun `scatter with numeric labels parses`() {
        val spec = ChartSpecs.parse(obj("""{"type":"scatter","labels":["1.5","2","3"],"series":[{"values":[3,4,5]}]}""")).getOrThrow()
        assertEquals("scatter", spec.type)
    }

    @Test fun `axis math`() {
        val a = ChartMath.niceAxis(3.0, 97.0, includeZero = true)
        assertEquals(0.0, a.min, 0.0); assertTrue(a.max >= 97.0); assertEquals(20.0, a.step, 1e-9)
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), a.ticks)
        val b = ChartMath.niceAxis(5.0, 5.0, includeZero = false)
        assertTrue(b.min < 5.0 && b.max > 5.0)
        val z = ChartMath.niceAxis(0.0, 0.0, includeZero = true)
        assertTrue(z.max > 0.0)
        assertEquals(0.5, ChartMath.niceAxis(0.0, 10.0, true).frac(5.0), 1e-9)
    }

    @Test fun `value formatting`() {
        assertEquals("1,234", ChartMath.format(1234.0))
        assertEquals("1.23M", ChartMath.format(1_234_567.0))
        assertEquals("2B", ChartMath.format(2e9))
        assertEquals("0.5", ChartMath.format(0.5))
        assertEquals("12.3", ChartMath.format(12.30))
        assertEquals("-7", ChartMath.format(-7.0))
    }

    @Test fun `pie slices cover 360 degrees from 12 o'clock`() {
        val s = ChartMath.pieSlices(listOf(1.0, 2.0, 3.0))
        assertEquals(-90f, s[0].first, 1e-4f)
        assertEquals(360f, s.sumOf { it.second.toDouble() }.toFloat(), 1e-3f)
        assertEquals(s[0].first + s[0].second, s[1].first, 1e-4f)
        assertEquals(1, ChartMath.labelStride(10, 100f, 50f)); assertEquals(3, ChartMath.labelStride(10, 30f, 70f))
    }

    @Test fun `tool writes png and returns a renderable result`() = runTest {
        val dir = Files.createTempDirectory("charts").toFile()
        val tool = ChartTool(dir, { _, f -> f.writeBytes(byteArrayOf(1, 2, 3)); true }, now = { 42L })
        val r = tool.execute(obj("""{"type":"bar","title":"T","labels":["a","b"],"series":[{"name":"s","values":[1,2]}]}"""))
        val o = Json.parseToJsonElement(r).jsonObject
        assertEquals(true, o["ok"]!!.jsonPrimitive.boolean)
        assertEquals(java.io.File(dir, "chart-42.png").absolutePath, o["image_path"]!!.jsonPrimitive.content)
        val (spec, png) = ChartSpecs.fromResult(r)!!
        assertEquals("T", spec.title); assertEquals(listOf(1.0, 2.0), spec.series[0].values); assertNotNull(png)
        assertEquals(listOf("", "s") to listOf(listOf("a", "1"), listOf("b", "2")), spec.toRows())
        dir.deleteRecursively()
    }

    @Test fun `tool without png still succeeds and bad input is an error`() = runTest {
        val dir = Files.createTempDirectory("charts").toFile()
        val tool = ChartTool(dir, { _, _ -> throw RuntimeException("no canvas") })
        val o = Json.parseToJsonElement(tool.execute(obj("""{"type":"pie","labels":["a"],"series":[[1]]}"""))).jsonObject
        assertEquals(true, o["ok"]!!.jsonPrimitive.boolean); assertNull(o["image_path"]); assertNotNull(o["png_error"])
        assertNotNull(ChartSpecs.fromResult(o.toString()))
        val bad = tool.execute(obj("""{"type":"bar"}"""))
        assertTrue(bad, bad.contains("series is required")); assertNull(ChartSpecs.fromResult(bad))
        assertNull(ChartSpecs.fromResult(null)); assertNull(ChartSpecs.fromResult("not json"))
        dir.deleteRecursively()
    }
}
