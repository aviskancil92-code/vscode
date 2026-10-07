package com.vscode.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class NetTest {

    @Test
    fun allowsRedirectToExplicitlyAllowedHttpsHost() {
        assertEquals(
            "https://release-assets.githubusercontent.com/download/file",
            Net.resolveAllowedRedirect(
                "https://github.com/example/release",
                "https://release-assets.githubusercontent.com/download/file"
            )
        )
    }

    @Test
    fun allowsRedirectToOfficialLinuxContainersMirrorSubdomain() {
        val mirror = "https://sgp1mirror01.do.images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261007_05%3A24/SHA256SUMS"
        assertEquals(
            mirror,
            Net.resolveAllowedRedirect(
                "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261007_05%3A24/SHA256SUMS",
                mirror
            )
        )
    }

    @Test
    fun rejectsLookalikeLinuxContainersHostAndNonstandardMirrorPort() {
        expectIOException { Net.validateAllowedUrl("https://evilimages.linuxcontainers.org/file") }
        expectIOException {
            Net.validateAllowedUrl("https://sgp1mirror01.do.images.linuxcontainers.org:444/file")
        }
    }

    @Test
    fun resolvesRelativeRedirectOnCurrentAllowedHost() {
        assertEquals(
            "https://packages.termux.dev/dists/stable/Packages",
            Net.resolveAllowedRedirect("https://packages.termux.dev/dists/stable/", "Packages")
        )
    }

    @Test
    fun rejectsRedirectToUnlistedHost() {
        expectIOException {
            Net.resolveAllowedRedirect(
                "https://github.com/example/release",
                "https://attacker.example/payload"
            )
        }
    }

    @Test
    fun rejectsHttpsDowngradeAndCredentialUrl() {
        expectIOException {
            Net.resolveAllowedRedirect(
                "https://packages.termux.dev/index",
                "http://packages.termux.dev/redirect"
            )
        }
        expectIOException { Net.validateAllowedUrl("https://user@github.com/file") }
        expectIOException { Net.validateAllowedUrl("https://github.com:444/file") }
    }

    @Test
    fun boundedMetadataReaderAcceptsUnderLimitAndRejectsOverflow() {
        assertEquals(
            "four",
            Net.readBoundedText(ByteArrayInputStream("four".toByteArray()), maxBytes = 4)
        )
        expectIOException {
            Net.readBoundedText(ByteArrayInputStream("12345".toByteArray()), maxBytes = 4)
        }
    }

    @Test
    fun artifactByteCounterAcceptsExactLimitAndRejectsOverflow() {
        assertEquals(4L, Net.checkedByteCount(currentBytes = 2L, incomingBytes = 2, maxBytes = 4L))
        expectIOException {
            Net.checkedByteCount(currentBytes = 4L, incomingBytes = 1, maxBytes = 4L)
        }
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
            // Expected policy rejection.
        }
    }
}
