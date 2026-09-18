package io.colens.report.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HistorianPathsTest : StringSpec({

    val defaults = HistorianPaths.Defaults(historian = "Plant", gateway = "gw1", tagProvider = "default")

    "a bare path is fully qualified from the defaults" {
        HistorianPaths.qualify("Line1/Temp", defaults) shouldBe
            "histprov:Plant:/sys:gw1:/prov:default:/tag:Line1/Temp"
    }

    "the legacy bracketed form names the tag provider" {
        HistorianPaths.qualify("[Plant2]Line1/Temp", defaults) shouldBe
            "histprov:Plant:/sys:gw1:/prov:Plant2:/tag:Line1/Temp"
    }

    "an empty bracket falls back to the default provider" {
        HistorianPaths.qualify("[]Line1/Temp", defaults) shouldBe
            "histprov:Plant:/sys:gw1:/prov:default:/tag:Line1/Temp"
    }

    "an already-qualified path passes through untouched, even naming another gateway" {
        // A report legitimately spans sources; rewriting this would silently redirect the query
        // to the local gateway and return the wrong plant's numbers.
        val qualified = "histprov:Other:/sys:gw9:/prov:remote:/tag:Line2/Flow"
        HistorianPaths.qualify(qualified, defaults) shouldBe qualified
    }

    "surrounding whitespace is tolerated, because cell references carry it" {
        HistorianPaths.qualify("  Line1/Temp  ", defaults) shouldBe
            "histprov:Plant:/sys:gw1:/prov:default:/tag:Line1/Temp"
    }

    "an empty path is rejected rather than qualified into nonsense" {
        shouldThrow<IllegalArgumentException> { HistorianPaths.qualify("   ", defaults) }
    }

    "a provider with no tag is rejected" {
        shouldThrow<IllegalArgumentException> { HistorianPaths.qualify("[default]", defaults) }
    }

    "displayName strips the qualification for use as a column heading" {
        HistorianPaths.displayName("histprov:Plant:/sys:gw1:/prov:default:/tag:Line1/Temp") shouldBe "Line1/Temp"
    }

    "displayName leaves an unqualified path alone" {
        HistorianPaths.displayName("Line1/Temp") shouldBe "Line1/Temp"
    }
})
