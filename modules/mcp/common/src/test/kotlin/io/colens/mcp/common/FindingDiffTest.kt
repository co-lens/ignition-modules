package io.colens.mcp.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private fun error(path: String, code: String = "invalid_binding_config", message: String = "bad") =
    Finding(path, code, Severity.ERROR, message)

private fun warning(path: String, code: String = "missing_name", message: String = "unnamed") =
    Finding(path, code, Severity.WARNING, message)

class FindingDiffTest : StringSpec({

    "everything is introduced when nothing was wrong before" {
        val after = listOf(error("root/A"), warning("root/B"))
        val diff = diffFindings(before = emptyList(), after = after)
        diff.introduced shouldContainExactly after
        diff.preExisting.shouldBeEmpty()
    }

    "an unchanged view introduces nothing" {
        val findings = listOf(error("root/A"), error("root/B", message = "other"))
        val diff = diffFindings(before = findings, after = findings)
        diff.introduced.shouldBeEmpty()
        diff.preExisting shouldContainExactly findings
    }

    // The reason the key excludes `path`. perspective_move_component rewrites the path of the
    // moved node and every descendant; keying on path would refuse the move.
    "a pre-existing finding that moved is not introduced" {
        val diff = diffFindings(
            before = listOf(error("root/Broken")),
            after = listOf(error("root/Box/Broken")),
        )
        diff.introduced.shouldBeEmpty()
        diff.preExisting.single().path shouldBe "root/Box/Broken"
    }

    "a rename does not make an existing finding look new" {
        val diff = diffFindings(
            before = listOf(error("root/Old")),
            after = listOf(error("root/Renamed")),
        )
        diff.introduced.shouldBeEmpty()
    }

    "a genuinely new error is introduced alongside an untouched one" {
        val stale = error("root/Broken")
        val fresh = error("root/Edited", code = "invalid_prop", message = "'text' is not a string")
        val diff = diffFindings(before = listOf(stale), after = listOf(stale, fresh))
        diff.introduced shouldContainExactly listOf(fresh)
        diff.preExisting shouldContainExactly listOf(stale)
    }

    // Counting, not set membership: duplicating an already-broken binding must still be caught.
    "duplicating an existing finding introduces exactly one" {
        val diff = diffFindings(
            before = listOf(error("root/A")),
            after = listOf(error("root/A"), error("root/Copy")),
        )
        diff.introduced.single().path shouldBe "root/Copy"
        diff.preExisting.single().path shouldBe "root/A"
    }

    // Path is not part of the key, but it decides which of a group is reported as the new one.
    "the surplus is attributed to the path the edit touched" {
        val diff = diffFindings(
            before = listOf(error("root/A"), error("root/B")),
            after = listOf(error("root/A"), error("root/B"), error("root/New")),
        )
        diff.introduced.single().path shouldBe "root/New"
    }

    "a finding the edit fixed simply disappears" {
        val diff = diffFindings(
            before = listOf(error("root/A"), error("root/B", message = "other")),
            after = listOf(error("root/A")),
        )
        diff.introduced.shouldBeEmpty()
        diff.preExisting.single().path shouldBe "root/A"
    }

    // Why the key is not `code` alone: the counts match, but the problem is a different one.
    "same code with a different message is introduced" {
        val diff = diffFindings(
            before = listOf(error("root/A", code = "invalid_prop", message = "'text' is wrong")),
            after = listOf(error("root/B", code = "invalid_prop", message = "'value' is wrong")),
        )
        diff.introduced.single().message shouldBe "'value' is wrong"
    }

    "warnings are partitioned too, not dropped" {
        val stale = warning("root/A")
        val fresh = warning("root/B", message = "also unnamed")
        val diff = diffFindings(before = listOf(stale), after = listOf(stale, fresh))
        diff.introduced shouldContainExactly listOf(fresh)
        diff.preExisting shouldContainExactly listOf(stale)
    }

    "an empty result partitions to nothing" {
        val diff = diffFindings(before = listOf(error("root/A")), after = emptyList())
        diff.introduced.shouldBeEmpty()
        diff.preExisting.shouldBeEmpty()
    }

    "the two lists are always a partition of the post-edit findings, in order" {
        val after = listOf(
            error("root/A"),
            warning("root/B"),
            error("root/C", message = "new"),
            error("root/D", message = "new"),
        )
        val diff = diffFindings(before = listOf(error("root/A"), warning("root/B")), after = after)
        (diff.introduced + diff.preExisting).toSet() shouldBe after.toSet()
        diff.introduced.size + diff.preExisting.size shouldBe after.size
        diff.introduced shouldContainExactly after.filter { it.message == "new" }
        diff.preExisting shouldContainExactly after.filter { it.message != "new" }
    }
})
