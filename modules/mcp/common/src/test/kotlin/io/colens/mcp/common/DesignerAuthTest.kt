package io.colens.mcp.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val SECRET = "0123456789abcdef0123456789abcdef"

class DesignerAuthTest : StringSpec({

    // -- unconfigured: the default, and the reason this class exists ---------

    "with no secret configured, nothing is required" {
        DesignerAuth(null).required shouldBe false
    }

    "with no secret configured, a request with no header is authorised" {
        DesignerAuth(null).authorize(null) shouldBe true
    }

    // The compatibility requirement. A client configured before this change still sends last
    // session's secret; rejecting it would reintroduce the staleness we are removing.
    "with no secret configured, a stale Bearer header is still authorised" {
        DesignerAuth(null).authorize("Bearer 3f2a9c1d4e5b6a7f8091a2b3c4d5e6f7") shouldBe true
    }

    "with no secret configured, even a malformed header is authorised" {
        val auth = DesignerAuth(null)
        auth.authorize("Basic zzz") shouldBe true
        auth.authorize("") shouldBe true
        auth.authorize("Bearer") shouldBe true
    }

    "a blank or whitespace-only property counts as unset" {
        DesignerAuth("").required shouldBe false
        DesignerAuth("   ").required shouldBe false
        DesignerAuth("\t\n").authorize(null) shouldBe true
    }

    // -- configured ---------------------------------------------------------

    "the configured secret authorises" {
        DesignerAuth(SECRET).authorize("Bearer $SECRET") shouldBe true
    }

    "a wrong or missing credential is rejected" {
        val auth = DesignerAuth(SECRET)
        auth.required shouldBe true
        auth.authorize(null) shouldBe false
        auth.authorize("Bearer wrong") shouldBe false
        auth.authorize("Basic $SECRET") shouldBe false
        auth.authorize(SECRET) shouldBe false
    }

    "the Bearer prefix is matched case-insensitively" {
        DesignerAuth(SECRET).authorize("bearer $SECRET") shouldBe true
        DesignerAuth(SECRET).authorize("BEARER $SECRET") shouldBe true
    }

    "surrounding whitespace on the presented value is tolerated" {
        DesignerAuth(SECRET).authorize("Bearer   $SECRET  ") shouldBe true
    }

    "the property value is trimmed before use" {
        DesignerAuth("  $SECRET  ").authorize("Bearer $SECRET") shouldBe true
    }

    // Guards the constant-time compare: MessageDigest.isEqual checks length first, so neither a
    // prefix nor an extension of the secret may pass.
    "a prefix or an extension of the secret is rejected" {
        val auth = DesignerAuth(SECRET)
        auth.authorize("Bearer ${SECRET.dropLast(1)}") shouldBe false
        auth.authorize("Bearer ${SECRET}x") shouldBe false
    }

    "Bearer without a separating space is rejected" {
        DesignerAuth(SECRET).authorize("Bearer$SECRET") shouldBe false
    }

    "a multi-byte secret round-trips" {
        val unicode = "sécret-ключ-🔑-padding-padding-padding"
        DesignerAuth(unicode).authorize("Bearer $unicode") shouldBe true
        DesignerAuth(unicode).authorize("Bearer sécret-ключ-🔑-padding-padding-paddinq") shouldBe false
    }

    "the trimmed secret is exposed for the dialog and the discovery file" {
        DesignerAuth("  $SECRET  ").secret shouldBe SECRET
        DesignerAuth(null).secret shouldBe null
    }

    // -- advisory length ----------------------------------------------------

    "a short secret is flagged but still works" {
        val auth = DesignerAuth("tooshort")
        auth.secretIsShort shouldBe true
        auth.authorize("Bearer tooshort") shouldBe true
    }

    "a secret of exactly the minimum length is not flagged" {
        SECRET.length shouldBe DesignerAuth.MIN_SECRET_LENGTH
        DesignerAuth(SECRET).secretIsShort shouldBe false
    }

    "an unset secret is not flagged as short" {
        DesignerAuth(null).secretIsShort shouldBe false
    }
})
