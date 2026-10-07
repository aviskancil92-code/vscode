package com.vscode.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class ArchiveTest {

    @Test
    fun acceptsRootDirectoryEntriesDotAndDotSlash() = runBlocking {
        val sandbox = Files.createTempDirectory("codex-root-entry-test-")
        val output = Files.createDirectory(sandbox.resolve("extract"))
        try {
            val tar = tarArchive(
                TarEntry(name = ".", type = '5'),
                TarEntry(name = "./", type = '5'),
                TarEntry(name = "etc/hostname", data = "codex-test".toByteArray())
            )

            Archive.extractTar(ByteArrayInputStream(tar), output.toFile())

            assertEquals("codex-test", readUtf8(output.resolve("etc/hostname")))
        } finally {
            deleteTree(sandbox)
        }
    }

    @Test
    fun rejectsWriteThroughSymlinkOutsideExtractionRoot() = runBlocking {
        val sandbox = Files.createTempDirectory("codex-archive-test-")
        val output = Files.createDirectory(sandbox.resolve("extract"))
        val outside = Files.createDirectory(sandbox.resolve("outside"))
        val escaped = outside.resolve("payload.txt")
        try {
            val tar = tarArchive(
                TarEntry(name = "pivot", type = '2', link = "../outside"),
                TarEntry(name = "pivot/payload.txt", data = "controlled".toByteArray())
            )

            Archive.extractTar(ByteArrayInputStream(tar), output.toFile())

            assertFalse(
                "tar extraction wrote through a symlink outside outputDir: $escaped",
                Files.exists(escaped, LinkOption.NOFOLLOW_LINKS)
            )
            assertTrue("unsafe archive should fail closed", false)
        } catch (e: java.io.IOException) {
            assertFalse(
                "partial extraction must not write outside outputDir",
                Files.exists(escaped, LinkOption.NOFOLLOW_LINKS)
            )
        } finally {
            deleteTree(sandbox)
        }
    }

    @Test
    fun rejectsHardlinkSourceResolvedOutsideExtractionRoot() = runBlocking {
        val sandbox = Files.createTempDirectory("codex-hardlink-test-")
        val output = Files.createDirectory(sandbox.resolve("extract"))
        val outside = Files.createDirectory(sandbox.resolve("outside"))
        val source = outside.resolve("seed.txt")
        Files.newOutputStream(source).use { it.write("must remain outside".toByteArray()) }
        val destination = output.resolve("stolen.txt")
        try {
            val tar = tarArchive(
                TarEntry(name = "pivot", type = '2', link = "../outside"),
                TarEntry(name = "stolen.txt", type = '1', link = "pivot/seed.txt")
            )

            var rejected = false
            try {
                Archive.extractTar(ByteArrayInputStream(tar), output.toFile())
            } catch (_: java.io.IOException) {
                rejected = true
            }

            assertTrue("hardlink source escaping outputDir must be rejected", rejected)
            assertFalse("hardlink destination must not be created", Files.exists(destination))
            assertTrue("outside source must remain unchanged", readUtf8(source) == "must remain outside")
        } finally {
            deleteTree(sandbox)
        }
    }

    @Test
    fun absoluteGuestSymlinkIsMappedInsideExtractionRoot() = runBlocking {
        val sandbox = Files.createTempDirectory("codex-guest-link-test-")
        val output = Files.createDirectory(sandbox.resolve("extract"))
        try {
            val tar = tarArchive(
                TarEntry(name = "usr", type = '5'),
                TarEntry(name = "usr/bin", type = '5'),
                TarEntry(name = "bin", type = '2', link = "/usr/bin"),
                TarEntry(name = "bin/tool", data = "inside-rootfs".toByteArray())
            )

            Archive.extractTar(ByteArrayInputStream(tar), output.toFile())

            assertTrue(Files.isSymbolicLink(output.resolve("bin")))
            assertTrue(Files.readSymbolicLink(output.resolve("bin")).toString() != "/usr/bin")
            assertTrue(readUtf8(output.resolve("usr/bin/tool")) == "inside-rootfs")
        } finally {
            deleteTree(sandbox)
        }
    }

    @Test
    fun relativeGuestSymlinkRemainsUsable() = runBlocking {
        val sandbox = Files.createTempDirectory("codex-relative-link-test-")
        val output = Files.createDirectory(sandbox.resolve("extract"))
        try {
            val tar = tarArchive(
                TarEntry(name = "usr", type = '5'),
                TarEntry(name = "usr/bin", type = '5'),
                TarEntry(name = "bin", type = '2', link = "usr/bin"),
                TarEntry(name = "bin/tool", data = "relative-link-ok".toByteArray())
            )

            Archive.extractTar(ByteArrayInputStream(tar), output.toFile())

            assertTrue(Files.isSymbolicLink(output.resolve("bin")))
            assertTrue(readUtf8(output.resolve("usr/bin/tool")) == "relative-link-ok")
        } finally {
            deleteTree(sandbox)
        }
    }

    @Test
    fun expandedByteBudgetIncludesAllWrittenCopies() {
        assertTrue(Archive.checkedExpandedByteCount(8L, 2L, maxBytes = 10L) == 10L)
        var rejected = false
        try {
            Archive.checkedExpandedByteCount(10L, 1L, maxBytes = 10L)
        } catch (_: java.io.IOException) {
            rejected = true
        }
        assertTrue("any additional hardlink/file bytes beyond the limit must be rejected", rejected)
    }

    @Test
    fun extractsDebDataTarThroughBoundedTemporaryFile() = runBlocking {
        val sandbox = Files.createTempDirectory("codex-deb-stream-test-")
        val deb = sandbox.resolve("fixture.deb")
        val output = sandbox.resolve("extract")
        try {
            Files.write(deb, debArchive(tarArchive(TarEntry("usr/bin/tool", data = "streamed".toByteArray()))))
            Archive.extractDebToDir(deb.toFile(), output.toFile())

            assertTrue(readUtf8(output.resolve("usr/bin/tool")) == "streamed")
            val leftovers = Files.list(sandbox).use { paths ->
                paths.filter { it.fileName.toString().startsWith("deb-data-") }.count()
            }
            assertEquals(0L, leftovers)
        } finally {
            deleteTree(sandbox)
        }
    }

    private data class TarEntry(
        val name: String,
        val type: Char = '0',
        val data: ByteArray = byteArrayOf(),
        val link: String = ""
    )

    private fun tarArchive(vararg entries: TarEntry): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(512)
            put(header, 0, 100, entry.name)
            putOctal(header, 100, 8, if (entry.type == '2') 0x1FF else 0x1A4)
            putOctal(header, 108, 8, 0)
            putOctal(header, 116, 8, 0)
            putOctal(header, 124, 12, entry.data.size.toLong())
            putOctal(header, 136, 12, 0)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = entry.type.code.toByte()
            put(header, 157, 100, entry.link)
            put(header, 257, 6, "ustar\u0000")
            put(header, 263, 2, "00")
            val checksum = header.sumOf { it.toInt() and 0xFF }
            put(header, 148, 8, String.format("%06o\u0000 ", checksum))
            out.write(header)
            out.write(entry.data)
            val padding = (512 - entry.data.size % 512) % 512
            if (padding > 0) out.write(ByteArray(padding))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun debArchive(dataTar: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
        val header = ByteArray(60) { ' '.code.toByte() }
        put(header, 0, 16, "data.tar/")
        put(header, 16, 12, "0")
        put(header, 28, 6, "0")
        put(header, 34, 6, "0")
        put(header, 40, 8, "100644")
        put(header, 48, 10, dataTar.size.toString())
        put(header, 58, 2, "`\n")
        out.write(header)
        out.write(dataTar)
        if (dataTar.size % 2 != 0) out.write('\n'.code)
        return out.toByteArray()
    }

    private fun put(header: ByteArray, offset: Int, size: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= size)
        System.arraycopy(bytes, 0, header, offset, bytes.size)
    }

    private fun putOctal(header: ByteArray, offset: Int, size: Int, value: Long) {
        put(header, offset, size, String.format("%0${size - 1}o\u0000", value))
    }

    private fun deleteTree(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { children ->
                for (child in children) deleteTree(child)
            }
        }
        Files.deleteIfExists(path)
    }

    private fun readUtf8(path: Path): String =
        String(Files.newInputStream(path).use { it.readBytes() }, Charsets.UTF_8)
}
