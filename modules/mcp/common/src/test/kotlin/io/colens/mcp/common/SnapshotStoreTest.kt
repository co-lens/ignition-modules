package io.colens.mcp.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.style.StringSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

class SnapshotStoreTest : StringSpec({

    fun files(root: Path, category: String): List<Path> =
        root.resolve(category).takeIf { Files.isDirectory(it) }
            ?.let { Files.list(it).use { s -> s.filter(Files::isRegularFile).sorted().toList() } }
            ?: emptyList()

    "writes the content it was given" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root })

        val written = store.snapshotOnce("tag:[default]A", SnapshotStore.TAGS, "A") { """{"tags":[]}""" }

        written shouldNotBe null
        written!!.readText() shouldBe """{"tags":[]}"""
        files(root, SnapshotStore.TAGS) shouldHaveSize 1
    }

    // The whole point of "first touch per session": twelve edits to one view leave one copy of what
    // it looked like before any of them.
    "snapshots a key only once" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root })

        store.snapshotOnce("view:P:Page/Main", SnapshotStore.VIEWS, "Main") { "first" } shouldNotBe null
        repeat(11) {
            store.snapshotOnce("view:P:Page/Main", SnapshotStore.VIEWS, "Main") { "later" } shouldBe null
        }

        val kept = files(root, SnapshotStore.VIEWS)
        kept shouldHaveSize 1
        kept.single().readText() shouldBe "first"
    }

    "keys are independent" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root })

        store.snapshotOnce("view:P:One", SnapshotStore.VIEWS, "One") { "a" }
        store.snapshotOnce("view:P:Two", SnapshotStore.VIEWS, "Two") { "b" }

        files(root, SnapshotStore.VIEWS) shouldHaveSize 2
    }

    // A tag or view that does not exist yet has no prior state. That is not a failure, and must not
    // block the write that is about to create it.
    "null content writes nothing and does not throw" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root })

        store.snapshotOnce("tag:[default]New", SnapshotStore.TAGS, "New") { null } shouldBe null
        files(root, SnapshotStore.TAGS).shouldHaveSize(0)
    }

    // Fail closed. Callers let this propagate, so an edit whose backup failed never happens.
    "throws when the current state cannot be read" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root })

        val e = shouldThrow<McpArgumentException> {
            store.snapshotOnce("tag:[default]A", SnapshotStore.TAGS, "A") { error("provider is down") }
        }
        e.message!! shouldContain "could not read the current state"
        e.message!! shouldContain "Nothing was changed"
    }

    "throws when the file cannot be written" {
        // A regular file where the category directory needs to be: createDirectories fails.
        val root = createTempDirectory("snap")
        Files.createFile(root.resolve(SnapshotStore.TAGS))
        val store = SnapshotStore(rootProvider = { root })

        val e = shouldThrow<McpArgumentException> {
            store.snapshotOnce("tag:[default]A", SnapshotStore.TAGS, "A") { "content" }
        }
        e.message!! shouldContain "could not write the pre-edit backup"
    }

    // Without this a failed backup would burn its key, and the retry would sail past the guard rail
    // reporting success while having preserved nothing.
    "a failed snapshot can be retried" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root })

        shouldThrow<McpArgumentException> {
            store.snapshotOnce("tag:[default]A", SnapshotStore.TAGS, "A") { error("transient") }
        }
        store.snapshotOnce("tag:[default]A", SnapshotStore.TAGS, "A") { "recovered" } shouldNotBe null
        files(root, SnapshotStore.TAGS).single().readText() shouldBe "recovered"
    }

    "prunes oldest first, per category" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root }, maxPerCategory = 3)

        repeat(6) { i -> store.snapshotOnce("k$i", SnapshotStore.TAGS, "tag$i") { "body$i" } }

        files(root, SnapshotStore.TAGS) shouldHaveSize 3
    }

    "a path label cannot escape the category directory" {
        val root = createTempDirectory("snap")
        val store = SnapshotStore(rootProvider = { root })

        val written = store.snapshotOnce("k", SnapshotStore.VIEWS, "../../etc/passwd") { "x" }

        written!!.parent shouldBe root.resolve(SnapshotStore.VIEWS)
    }
})
