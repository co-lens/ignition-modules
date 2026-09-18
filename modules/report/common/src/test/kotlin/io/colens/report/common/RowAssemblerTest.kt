package io.colens.report.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The assembler is where a streaming query becomes a spreadsheet block, and every failure here is
 * SILENT — a column shifted left, a row dropped, values landing under the wrong heading. All of it
 * produces a report that looks entirely reasonable and is wrong.
 *
 * These cases exist because a live historian cannot be stood up in CI, so this is where the logic
 * actually gets exercised. The gateway's collectors are deliberately thin enough that little is
 * left untested above this.
 */
class RowAssemblerTest : StringSpec({

    fun assembler(vararg keys: String, names: List<String> = emptyList()) =
        RowAssembler(keys.toList(), names) { "col:$it" }

    "columns lead with the timestamp" {
        assembler("a", "b").columns() shouldBe listOf("timestamp", "col:a", "col:b")
    }

    "supplied column names override the defaults, per position" {
        val a = assembler("a", "b", names = listOf("Flow"))
        a.columns() shouldBe listOf("timestamp", "Flow", "col:b")
    }

    "a blank supplied name falls back rather than producing an unlabelled column" {
        assembler("a", names = listOf("   ")).columns() shouldBe listOf("timestamp", "col:a")
    }

    "rows come out in time order regardless of the order points arrive" {
        // The provider makes no ordering guarantee. Appending in arrival order would produce a
        // chart that doubles back on itself.
        val a = assembler("a")
        a.record("a", 300, 3.0)
        a.record("a", 100, 1.0)
        a.record("a", 200, 2.0)
        a.rows() shouldBe listOf(listOf(100L, 1.0), listOf(200L, 2.0), listOf(300L, 3.0))
    }

    "points for different keys at the same instant share one row" {
        val a = assembler("a", "b")
        a.record("a", 100, 1.0)
        a.record("b", 100, 9.0)
        a.rows() shouldBe listOf(listOf(100L, 1.0, 9.0))
    }

    "a gap in one series stays null instead of shifting the row left" {
        // THE case this class exists for. A naive append gives [100, 1.0, 2.0] here, silently
        // presenting series a's second sample as series b's first.
        val a = assembler("a", "b")
        a.record("a", 100, 1.0)
        a.record("a", 200, 2.0)
        a.record("b", 200, 9.0)
        a.rows() shouldBe listOf(listOf(100L, 1.0, null), listOf(200L, 2.0, 9.0))
    }

    "a point for an unknown key is dropped rather than throwing" {
        val a = assembler("a")
        a.record("zzz", 100, 1.0)
        a.rows() shouldBe emptyList()
    }

    "null values are preserved as nulls, not skipped" {
        // Bad quality arrives as a null value; dropping it would make the row vanish entirely and
        // hide the gap the report is supposed to show.
        val a = assembler("a")
        a.record("a", 100, null)
        a.rows() shouldBe listOf(listOf(100L, null))
    }

    "a later point at the same timestamp and key overwrites" {
        val a = assembler("a")
        a.record("a", 100, 1.0)
        a.record("a", 100, 2.0)
        a.rows() shouldBe listOf(listOf(100L, 2.0))
    }

    "key failures are collected as warnings, not thrown" {
        val a = assembler("a", "b")
        a.recordKeyFailure("b", "Bad_Unsupported")
        a.record("a", 100, 1.0)
        // The good column still reports. One bad tag path must not blank the report.
        a.rows() shouldBe listOf(listOf(100L, 1.0, null))
        a.warnings() shouldBe listOf("col:b: Bad_Unsupported")
    }

    "repeated failures on one key are reported once" {
        val a = assembler("a")
        repeat(500) { a.recordKeyFailure("a", "Bad_Unsupported") }
        a.warnings() shouldBe listOf("col:a: Bad_Unsupported")
    }

    "an empty result is an empty row list, not an error" {
        val a = assembler("a")
        a.rows() shouldBe emptyList()
        a.warnings() shouldBe emptyList()
        a.columns() shouldBe listOf("timestamp", "col:a")
    }

    "a duplicated key keeps the first column and leaves the second empty" {
        // Asking for the same (path, aggregate) twice yields two columns but one published key.
        // Filling both from it would be a plausible-looking lie about two distinct series.
        val a = assembler("a", "a")
        a.record("a", 100, 1.0)
        a.rows() shouldBe listOf(listOf(100L, 1.0, null))
    }
})
