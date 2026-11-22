package dev.openstream.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTest {
    private val json = """{"tag_name":"v0.5.0","body":"Fixes","assets":[
        {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
        {"name":"openstream-0.5.0.apk","browser_download_url":"https://x/a.apk","size":104857600,"digest":"sha256:ABCDEF"}]}"""

    @Test fun picksTheApkAsset() =
        assertEquals(Release("0.5.0", "Fixes", "https://x/a.apk", 104857600, "abcdef"), parseRelease(json))

    @Test fun digestIsOptionalAndNotesMayBeNull() = assertEquals(
        Release("0.5.0", "", "https://x/a.apk", 5, null),
        parseRelease("""{"tag_name":"0.5.0","body":null,"assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk","size":5}]}"""),
    )

    @Test fun noApkMeansNoRelease() = assertNull(parseRelease("""{"tag_name":"v0.5.0","assets":[]}"""))

    @Test fun garbageMeansNoRelease() {
        assertNull(parseRelease("<html>"))
        assertNull(parseRelease("""{"message":"rate limited"}"""))
    }
}
