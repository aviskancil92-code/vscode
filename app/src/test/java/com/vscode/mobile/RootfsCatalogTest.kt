package com.vscode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RootfsCatalogTest {
    private val base = "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/"

    @Test
    fun parsesEncodedAndLiteralColonBuildLinksLikeOfficialDirectoryIndex() {
        val html = """
            <html><body>
            <a href="../">../</a>
            <a href="20261006_05%3A24/">20261006_05:24/</a>
            <a href="20261007_05:24/">20261007_05:24/</a>
            </body></html>
        """.trimIndent()

        assertEquals(
            listOf("20261007_05:24", "20261006_05%3A24"),
            RootfsCatalog.parseBuildDates(html)
        )
    }

    @Test
    fun resolvesNewestBuildWithRootfsChecksumFromManifest() {
        val newest = "20261007_05%3A24"
        val older = "20261006_05%3A24"
        val html = """
            <a href="$older/">$older/</a>
            <a href="$newest/">$newest/</a>
        """.trimIndent()
        val expectedSha = "be17e8c6fe2d9173a3fd82ed5d5cd814248a27d8e5cad54ab946a546c74cb590"

        val result = RootfsCatalog.resolve(base) { url ->
            when (url) {
                base -> html
                "$base$newest/SHA256SUMS" -> "$expectedSha  rootfs.tar.xz\n${"a".repeat(64)}  rootfs.squashfs\n"
                "$base$older/SHA256SUMS" -> "${"b".repeat(64)}  rootfs.tar.xz\n"
                else -> throw IOException("unexpected URL: $url")
            }
        }

        assertEquals(2, result.size)
        assertEquals("$base$newest/rootfs.tar.xz", result.first().url)
        assertEquals(expectedSha, result.first().sha256)
    }

    @Test
    fun reportsUnderlyingIndexNetworkFailureInsteadOfGenericEmptyList() {
        val error = expectIOException {
            RootfsCatalog.resolve(base) { throw IOException("HTTP 503 Service Unavailable") }
        }
        assertTrue(error.message.orEmpty().contains("HTTP 503 Service Unavailable"))
        assertTrue(error.message.orEmpty().contains("images.linuxcontainers.org"))
    }

    @Test
    fun distinguishesUnexpectedIndexBodyFromNetworkFailure() {
        val error = expectIOException {
            RootfsCatalog.resolve(base) { "<html>maintenance</html>" }
        }
        assertTrue(error.message.orEmpty().contains("tidak ditemukan tautan tanggal build"))
    }

    @Test
    fun reportsChecksumFailuresForAllRecentBuilds() {
        val date = "20261007_05%3A24"
        val html = "<a href=\"$date/\">$date/</a>"
        val error = expectIOException {
            RootfsCatalog.resolve(base) { url ->
                if (url == base) html else throw IOException("HTTP 502")
            }
        }
        assertTrue(error.message.orEmpty().contains(date))
        assertTrue(error.message.orEmpty().contains("HTTP 502"))
    }

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        throw AssertionError("expected IOException")
    }
}
