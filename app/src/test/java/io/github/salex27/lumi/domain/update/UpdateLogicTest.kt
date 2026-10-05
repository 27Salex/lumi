package io.github.salex27.lumi.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun v(s: String) = Semver.parse(s)!!

    @Test fun parsesTagsAndVersions() {
        assertEquals(Semver(1, 2, 3), v("v1.2.3"))
        assertEquals(Semver(1, 2, 0), v("1.2"))
        assertEquals(Semver(2, 0, 0, listOf("beta", "1")), v("V2.0.0-beta.1+build5"))
        assertNull(Semver.parse("latest"))
        assertNull(Semver.parse(""))
        assertNull(Semver.parse(null))
    }

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertEquals(0, v("v1.1.0").compareTo(v("1.1.0")))
    }

    @Test fun prereleaseRanksBelowRelease() {
        assertTrue(v("1.0.0") > v("1.0.0-rc.1"))
        assertTrue(v("1.0.0-rc.2") > v("1.0.0-rc.1"))
        assertTrue(v("1.0.0-rc.11") > v("1.0.0-rc.2"))
        assertTrue(v("1.0.0-beta") > v("1.0.0-alpha.9"))
        assertTrue(v("1.0.0-alpha.1") > v("1.0.0-alpha"))
        assertTrue(v("1.0.0-alpha") > v("1.0.0-1"))
    }

    @Test fun isNewerAgainstInstalled() {
        assertTrue(UpdateLogic.isNewer(v("1.2.0"), "1.1.0"))
        assertFalse(UpdateLogic.isNewer(v("1.1.0"), "1.1.0"))
        assertFalse(UpdateLogic.isNewer(v("1.0.9"), "1.1.0"))
        assertFalse(UpdateLogic.isNewer(v("1.2.0"), "garbage"))
    }

    private val sample = "{\"tag_name\":\"v1.2.0\",\"draft\":false,\"prerelease\":false," +
        "\"html_url\":\"https://github.com/27Salex/lumi/releases/tag/v1.2.0\"," +
        "\"body\":\"## New\\n- **Chats** screen\\n- Updates\\n\"," +
        "\"assets\":[{\"name\":\"notes.txt\",\"size\":5,\"browser_download_url\":\"https://x/notes.txt\"}," +
        "{\"name\":\"lumi-1.2.0.apk\",\"size\":1234,\"browser_download_url\":\"https://x/lumi.apk\"," +
        "\"digest\":\"sha256:" + "AB".repeat(32) + "\"}]}"

    @Test fun parsesReleaseWithApkAndDigest() {
        val r = (UpdateLogic.parseRelease(sample) as UpdateLogic.Parsed.Release).info
        assertEquals("v1.2.0", r.tag)
        assertEquals("lumi-1.2.0.apk", r.apkName)
        assertEquals("https://x/lumi.apk", r.apkUrl)
        assertEquals(1234L, r.apkSize)
        assertEquals("ab".repeat(32), r.sha256)
        assertTrue(r.hasApk)
    }

    @Test fun releaseWithoutApkOrDigest() {
        val r = (UpdateLogic.parseRelease("{\"tag_name\":\"1.3.0\",\"assets\":[]}") as UpdateLogic.Parsed.Release).info
        assertFalse(r.hasApk)
        assertNull(r.sha256)
        assertEquals(-1L, r.apkSize)
    }

    @Test fun ignoresDraftsPrereleasesAndGarbage() {
        assertEquals(UpdateLogic.Parsed.Ignored, UpdateLogic.parseRelease("{\"tag_name\":\"v2.0.0\",\"draft\":true}"))
        assertEquals(UpdateLogic.Parsed.Ignored, UpdateLogic.parseRelease("{\"tag_name\":\"v2.0.0\",\"prerelease\":true}"))
        assertEquals(UpdateLogic.Parsed.Ignored, UpdateLogic.parseRelease("{\"tag_name\":\"v2.0.0-rc.1\"}"))
        assertEquals(UpdateLogic.Parsed.Invalid, UpdateLogic.parseRelease("not json"))
        assertEquals(UpdateLogic.Parsed.Invalid, UpdateLogic.parseRelease("{\"message\":\"Not Found\"}"))
        assertEquals(UpdateLogic.Parsed.Invalid, UpdateLogic.parseRelease("{\"tag_name\":\"nightly\"}"))
    }

    @Test fun digestOnlyAcceptsSha256Hex() {
        assertNull(UpdateLogic.sha256Of("sha512:" + "a".repeat(64)))
        assertNull(UpdateLogic.sha256Of("sha256:zz"))
        assertEquals("a".repeat(64), UpdateLogic.sha256Of("sha256:" + "A".repeat(64)))
    }

    @Test fun checkIntervalIs12Hours() {
        val h = 3_600_000L
        assertTrue(UpdateLogic.shouldCheck(0, 100 * h))
        assertFalse(UpdateLogic.shouldCheck(100 * h, 111 * h))
        assertTrue(UpdateLogic.shouldCheck(100 * h, 112 * h))
        assertTrue(UpdateLogic.shouldCheck(100 * h, 90 * h)) // clock went backwards
    }

    @Test fun trimNotesStripsMarkdownAndCaps() {
        assertEquals("New\nChats screen\nUpdates", UpdateLogic.trimNotes("## New\n- **Chats** screen\n\n- Updates\n"))
        val long = UpdateLogic.trimNotes("word ".repeat(200), 50)
        assertTrue(long.length <= 51 && long.endsWith("…"))
    }
}
