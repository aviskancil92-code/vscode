package com.vscode.mobile

import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Pembaca arsip yang dibutuhkan installer:
 *  - tar (GNU longname/longlink + pax path/linkpath + symlink + hardlink)
 *  - kompresi XZ, GZIP, atau tanpa kompresi (deteksi otomatis dari magic bytes)
 *  - arsip .deb (format `ar`) untuk mengekstrak paket proot/libtalloc/libandroid-shmem
 *
 * Semua entri divalidasi terhadap path-traversal (zip-slip).
 */
object Archive {

    private const val MAX_METADATA_BYTES = 4L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 3L * 1024L * 1024L * 1024L
    private const val MAX_ENTRIES = 250_000
    private const val MAX_DEB_DATA_BYTES = 256L * 1024L * 1024L

    internal fun checkedExpandedByteCount(
        currentBytes: Long,
        additionalBytes: Long,
        maxBytes: Long = MAX_EXPANDED_BYTES
    ): Long {
        if (currentBytes < 0L || additionalBytes < 0L || maxBytes < 0L ||
            additionalBytes > maxBytes - currentBytes
        ) {
            throw IOException("ukuran hasil ekstraksi melewati batas aman")
        }
        return currentBytes + additionalBytes
    }

    /** InputStream penghitung byte mentah (untuk progres unduhan/ekstraksi). */
    class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val r = wrapped.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = wrapped.read(b, off, len)
            if (r > 0) count += r
            return r
        }

        override fun available(): Int = wrapped.available()
        override fun close() = wrapped.close()
    }

    /** Buka berkas tar terdeteksi-kompresi. Mengembalikan (counter, stream). */
    fun openTarFile(file: File): Pair<CountingInputStream, InputStream> {
        val counting = CountingInputStream(FileInputStream(file))
        val pushback = PushbackInputStream(counting, 16)
        val magic = ByteArray(6)
        var n = 0
        while (n < 6) {
            val r = pushback.read(magic, n, 6 - n)
            if (r < 0) break
            n += r
        }
        if (n < 6) throw IOException("berkas arsip terlalu kecil: ${file.name}")
        pushback.unread(magic, 0, n)

        val stream: InputStream = when {
            magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                GZIPInputStream(pushback)
            magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() &&
                magic[4] == 0x5A.toByte() ->
                XZInputStream(pushback)
            else -> pushback
        }
        return Pair(counting, stream)
    }

    private class TarHeader(val name: String, val mode: Int, val size: Long, val type: Char, val link: String)

    private fun readHeader(input: InputStream): TarHeader? {
        val block = ByteArray(512)
        if (!readFully(input, block)) return null
        var allZero = true
        for (b in block) {
            if (b.toInt() != 0) { allZero = false; break }
        }
        if (allZero) return null
        // Magic "ustar" wajib ada (GNU: "ustar  ", POSIX: "ustar\0").
        if (block[257] != 'u'.code.toByte() || block[258] != 's'.code.toByte() ||
            block[259] != 't'.code.toByte() || block[260] != 'a'.code.toByte() ||
            block[261] != 'r'.code.toByte()
        ) {
            throw IOException("header tar tidak valid")
        }
        val name = cstr(block, 0, 100)
        val mode = octal(block, 100, 8).toInt()
        // POSIX ustar: size berada di offset 124 (108 = uid, 116 = gid).
        // Bug lama: offset 108 membuat string uid+gid berisi NUL di tengah
        // -> NumberFormatException "under radix 8" saat entri pertama diekstrak.
        val size = octal(block, 124, 12)
        val type = block[156].toInt().toChar()
        val link = cstr(block, 157, 100)
        val prefix = cstr(block, 345, 155)
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
        return TarHeader(fullName, mode, size, type, link)
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8).trimEnd(' ')
    }

    /**
     * Baca field numerik oktar header tar (NUL/spasi = terminator, di posisi mana pun,
     * termasuk leading/trailing — aman untuk GNU, POSIX, dan varian BSD sekaligus),
     * plus format GNU base-256. Field kosong bernilai 0.
     */
    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { // GNU base-256
            var v = (b[off].toInt() and 0x7F).toLong()
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        var seen = false
        for (i in off until off + len) {
            val c = b[i].toInt() and 0xFF
            if (c >= '0'.code && c <= '7'.code) {
                seen = true
                v = v * 8 + (c - '0'.code)
            } else if (c == 0 || c == ' '.code) {
                if (seen) break // terminator setelah digit terakhir
                // spasi/NUL di depan (padding) — abaikan
            } else {
                throw IOException("field oktal tar tidak valid di offset $off")
            }
        }
        return v
    }

    private fun pad(size: Long): Long = (512L - (size % 512L)) % 512L

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var done = 0
        while (done < buf.size) {
            val n = input.read(buf, done, buf.size - done)
            if (n < 0) {
                if (done == 0) return false
                throw IOException("arsip tar terpotong")
            }
            done += n
        }
        return true
    }

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (r < 0) throw IOException("arsip tar terpotong saat melewati data")
            remaining -= r
        }
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_METADATA_BYTES || size > Int.MAX_VALUE) {
            throw IOException("metadata arsip terlalu besar: $size byte")
        }
        val out = ByteArray(size.toInt())
        var done = 0
        while (done < out.size) {
            val n = input.read(out, done, out.size - done)
            if (n < 0) throw IOException("arsip tar terpotong saat membaca data")
            done += n
        }
        skipFully(input, pad(size))
        return out
    }

    private fun copyExactly(input: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("arsip tar terpotong saat menulis berkas")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun parsePax(text: String): Pair<String?, String?> {
        var path: String? = null
        var linkpath: String? = null
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0 || i + len > text.length) break
            val rec = text.substring(i, i + len) // "<len> key=value\n"
            val kv = rec.substring(rec.indexOf(' ') + 1).trimEnd('\n')
            val eq = kv.indexOf('=')
            if (eq > 0) {
                when (kv.substring(0, eq)) {
                    "path" -> path = kv.substring(eq + 1)
                    "linkpath" -> linkpath = kv.substring(eq + 1)
                }
            }
            i += len
        }
        return Pair(path, linkpath)
    }

    /** Tolak path lexical maupun symlink parent yang membawa operasi ke luar root ekstraksi. */
    private fun requireInsideRoot(rootPath: Path, path: Path, description: String) {
        if (path != rootPath && !path.startsWith(rootPath)) {
            throw IOException("path arsip di luar direktori tujuan: $description")
        }
        val canonical = path.toFile().canonicalFile.toPath()
        if (canonical != rootPath && !canonical.startsWith(rootPath)) {
            throw IOException("path arsip melewati symlink ke luar direktori tujuan: $description")
        }
    }

    /** Resolve nama entri dan validasi parent setelah seluruh symlink yang ada di-resolve. */
    private fun safeTarget(root: File, name: String): File {
        val rootPath = root.canonicalFile.toPath()
        val rel = name.removePrefix("./").removePrefix("/")
        if (rel.isEmpty()) return root
        val target = rootPath.resolve(rel).normalize()
        if (target != rootPath && !target.startsWith(rootPath)) {
            throw IOException("entri tar tidak aman (di luar direktori tujuan): $name")
        }
        // Tar rootfs lazim berisi entri direktori `.` atau `./`; root sendiri valid,
        // tetapi parent-nya memang berada di luar root ekstraksi dan tidak perlu dicek.
        if (target == rootPath) return root
        requireInsideRoot(rootPath, target.parent ?: rootPath, name)
        return target.toFile()
    }

    /**
     * Symlink arsip memakai namespace guest: target absolut seperti /usr/bin berarti
     * <rootfs>/usr/bin, bukan /usr/bin pada host. Target relatif tetap relatif ke parent
     * entri. Link disimpan sebagai path relatif agar tidak menunjuk ke root host.
     */
    private fun safeSymlinkTarget(root: File, dst: File, link: String): Path {
        if (link.isEmpty()) throw IOException("target symlink kosong")
        val rootPath = root.canonicalFile.toPath()
        val dstPath = dst.toPath().toAbsolutePath().normalize()
        val parent = dstPath.parent ?: rootPath
        val raw = try {
            Paths.get(link)
        } catch (e: Exception) {
            throw IOException("target symlink tidak valid: $link", e)
        }
        val target = if (raw.isAbsolute) {
            rootPath.resolve(link.removePrefix("/")).normalize()
        } else {
            parent.resolve(raw).normalize()
        }
        requireInsideRoot(rootPath, target, link)
        return parent.relativize(target)
    }

    /** Hapus leaf lama tanpa mengikuti symlink; file output baru tidak pernah menulis lewatnya. */
    private fun prepareFileDestination(root: File, dst: File) {
        val rootPath = root.canonicalFile.toPath()
        val dstPath = dst.toPath()
        requireInsideRoot(rootPath, dstPath.parent ?: rootPath, dst.path)
        if (Files.isSymbolicLink(dstPath)) Files.delete(dstPath)
        if (Files.exists(dstPath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(dstPath, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("tujuan file arsip bertabrakan dengan direktori: ${dst.path}")
            }
            Files.delete(dstPath)
        }
    }

    /** Direktori: owner wajib rwx (rootfs punya dir 0555; tanpa ini isi di dalamnya gagal ditulis). */
    private fun dirMode(mode: Int): Int = (if (mode != 0) mode else 0x1ED) or 0x1C0 // 0700
    /** Berkas: owner wajib rw (berkas 0444/0000 tetap bisa ditimpa/dibaca proot). */
    private fun fileMode(mode: Int): Int = (if (mode != 0) mode else 0x1A4) or 0x180 // 0600

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode and 0x1FF) } catch (_: Exception) { }
    }

    /**
     * Ekstrak seluruh isi tarball ke [outputDir].
     * [onProgress] menerima jumlah byte terkompresi yang telah diproses.
     * @return jumlah entri yang diekstrak.
     */
    suspend fun extractTar(input: InputStream, outputDir: File, onProgress: suspend (Long) -> Unit = {}): Int {
        outputDir.mkdirs()
        var entries = 0
        var expandedBytes = 0L
        var lastReport = 0L
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null

        while (true) {
            val hdr = readHeader(input) ?: break

            if (++entries > MAX_ENTRIES) throw IOException("arsip memiliki terlalu banyak entri")
            if (hdr.size < 0 || hdr.size > MAX_ENTRY_BYTES) {
                throw IOException("entri arsip terlalu besar: ${hdr.size} byte")
            }

            when (hdr.type) {
                'L' -> { pendingLongName = readData(input, hdr.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n'); continue }
                'K' -> { pendingLongLink = readData(input, hdr.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n'); continue }
                'x', 'g' -> {
                    val (p, l) = parsePax(readData(input, hdr.size).toString(Charsets.UTF_8))
                    if (p != null) paxPath = p
                    if (l != null) paxLink = l
                    continue
                }
            }

            val name = (pendingLongName ?: paxPath ?: hdr.name).trimEnd('/')
            val link = pendingLongLink ?: paxLink ?: hdr.link
            pendingLongName = null; pendingLongLink = null; paxPath = null; paxLink = null

            if (name.isEmpty()) {
                if (hdr.size > 0) skipFully(input, hdr.size + pad(hdr.size))
                continue
            }

            when (hdr.type) {
                '5', 'D' -> {
                    val dir = safeTarget(outputDir, name)
                    if (Files.isSymbolicLink(dir.toPath())) {
                        throw IOException("entri direktori bertabrakan dengan symlink: $name")
                    }
                    dir.mkdirs()
                    if (!dir.isDirectory) throw IOException("gagal membuat direktori arsip: $name")
                    chmod(dir, dirMode(hdr.mode))
                }
                '2' -> { // symlink
                    val dst = safeTarget(outputDir, name)
                    val parent = dst.parentFile ?: throw IOException("symlink tanpa parent: $name")
                    parent.mkdirs()
                    requireInsideRoot(outputDir.canonicalFile.toPath(), parent.toPath(), name)
                    if (Files.exists(dst.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(dst.toPath())
                    Files.createSymbolicLink(dst.toPath(), safeSymlinkTarget(outputDir, dst, link))
                }
                '1' -> { // hardlink
                    val src = safeTarget(outputDir, link)
                    val dst = safeTarget(outputDir, name)
                    val parent = dst.parentFile ?: throw IOException("hardlink tanpa parent: $name")
                    parent.mkdirs()
                    val rootPath = outputDir.canonicalFile.toPath()
                    requireInsideRoot(rootPath, parent.toPath(), name)
                    if (Files.isSymbolicLink(src.toPath())) {
                        val srcPath = src.toPath()
                        val rawTarget = Files.readSymbolicLink(srcPath)
                        val sourceParent = srcPath.parent ?: rootPath
                        val target = if (rawTarget.isAbsolute) {
                            rootPath.resolve(rawTarget.toString().removePrefix("/")).normalize()
                        } else {
                            sourceParent.resolve(rawTarget).normalize()
                        }
                        requireInsideRoot(rootPath, target, link)
                        prepareFileDestination(outputDir, dst)
                        Files.createSymbolicLink(
                            dst.toPath(), dst.toPath().parent.relativize(target)
                        )
                    } else if (Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        val source = src.canonicalFile
                        requireInsideRoot(rootPath, source.toPath(), link)
                        if (!source.isFile) throw IOException("target hardlink bukan berkas biasa: $link")
                        val nextExpandedBytes = checkedExpandedByteCount(expandedBytes, source.length())
                        prepareFileDestination(outputDir, dst)
                        FileInputStream(source).use { i ->
                            FileOutputStream(dst).use { o -> copyExactly(i, o, source.length()) }
                        }
                        chmod(dst, fileMode(hdr.mode))
                        expandedBytes = nextExpandedBytes
                    } else {
                        throw IOException("target hardlink tidak ditemukan: $link")
                    }
                }
                '0', '\u0000', '7' -> { // berkas reguler
                    val dst = safeTarget(outputDir, name)
                    dst.parentFile?.mkdirs()
                    prepareFileDestination(outputDir, dst)
                    val nextExpandedBytes = checkedExpandedByteCount(expandedBytes, hdr.size)
                    FileOutputStream(dst).use { out -> copyExactly(input, out, hdr.size) }
                    // Data tar dipadding ke kelipatan 512 byte; WAJIB dilewati agar
                    // header berikutnya sejajar (tanpa ini: "header tar tidak valid").
                    skipFully(input, pad(hdr.size))
                    chmod(dst, fileMode(hdr.mode))
                    expandedBytes = nextExpandedBytes
                }
                else -> { // fifo/device/sparse dll — lewati datanya
                    if (hdr.size > 0) skipFully(input, hdr.size + pad(hdr.size))
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastReport >= 250) { // jangan banjiri UI thread tiap entri (puluhan ribu berkas)
                lastReport = now
                onProgress(entries.toLong())
            }
        }
        return entries
    }

    /**
     * Ekstrak berkas data.tar dari paket .deb (format ar) ke [outDir].
     */
    suspend fun extractDebToDir(deb: File, outDir: File) {
        val raf = RandomAccessFile(deb, "r")
        try {
            val magic = ByteArray(8)
            raf.readFully(magic)
            if (!magic.contentEquals(byteArrayOf(0x21, 0x3C, 0x61, 0x72, 0x63, 0x68, 0x3E, 0x0A))) {
                throw IOException("format .deb tidak valid (bukan arsip ar)")
            }
            var off = 8L
            var dataName: String? = null
            var dataOff = -1L
            var dataSize = -1L
            while (off + 60 <= raf.length()) {
                raf.seek(off)
                val hdr = ByteArray(60)
                raf.readFully(hdr)
                val name = String(hdr, 0, 16, Charsets.ISO_8859_1).trim(' ', '\u0000', '/')
                val size = String(hdr, 48, 10, Charsets.ISO_8859_1).trim(' ', '\u0000').toLong()
                if (name.startsWith("data.tar")) {
                    dataName = name; dataOff = off + 60; dataSize = size
                    break
                }
                off += 60 + size + (size and 1L)
            }
            val dn = dataName ?: throw IOException("data.tar tidak ditemukan dalam .deb")
            if (dataSize < 0 || dataSize > MAX_DEB_DATA_BYTES || dataSize > Int.MAX_VALUE) {
                throw IOException("data.tar dalam .deb terlalu besar: $dataSize byte")
            }
            if (!dn.endsWith(".xz") && !dn.endsWith(".gz") && !dn.endsWith(".tar")) {
                throw IOException("kompresi .deb tidak didukung: $dn")
            }

            outDir.parentFile?.mkdirs()
            outDir.mkdirs()
            val tempData = File.createTempFile("deb-data-", ".archive", outDir.parentFile ?: outDir)
            try {
                raf.seek(dataOff)
                FileOutputStream(tempData).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = dataSize
                    while (remaining > 0L) {
                        val n = raf.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (n < 0) throw IOException("data.tar terpotong dalam .deb")
                        out.write(buffer, 0, n)
                        remaining -= n
                    }
                    out.fd.sync()
                }

                val raw = FileInputStream(tempData)
                val stream = try {
                    when {
                        dn.endsWith(".xz") -> XZInputStream(raw)
                        dn.endsWith(".gz") -> GZIPInputStream(raw)
                        else -> raw
                    }
                } catch (e: Exception) {
                    raw.close()
                    throw e
                }
                try {
                    extractTar(stream, outDir)
                } finally {
                    stream.close()
                }
            } finally {
                tempData.delete()
            }
        } finally {
            raf.close()
        }
    }
}
