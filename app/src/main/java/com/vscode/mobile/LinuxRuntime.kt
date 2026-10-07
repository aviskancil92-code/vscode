package com.vscode.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Semua hal terkait eksekusi Linux: pemetaan arsitektur, konstruksi perintah
 * proot, lingkungan (environment), dan berkas konfigurasi guest.
 */
object LinuxRuntime {

    const val SERVER_PORT = 8080
    const val SERVER_URL = "http://127.0.0.1:$SERVER_PORT/"

    fun rootfsDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "debian")
    fun prootBin(ctx: Context): File = File(StateStore.linuxDir(ctx), "bin/proot")
    fun libDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "lib")
    fun tmpDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "tmp")
    fun logsDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "logs")
    fun shmDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "shm")
    fun fakeProcDir(ctx: Context): File = File(StateStore.linuxDir(ctx), "fakeproc")

    fun isInstalled(ctx: Context): Boolean {
        val st = StateStore.read(ctx)
        return st.installed &&
            prootBin(ctx).exists() &&
            File(rootfsDir(ctx), "opt/code-server/bin/code-server").exists() &&
            File(rootfsDir(ctx), "bin/bash").let { it.exists() || File(rootfsDir(ctx), "usr/bin/bash").exists() }
    }

    /** ABI perangkat -> (archTermux, archRootfsLXC, archCodeServer). */
    fun arches(): Triple<String, String, String>? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> Triple("aarch64", "arm64", "arm64")
        "armeabi-v7a" -> Triple("arm", "armhf", "armv7l")
        "x86_64" -> Triple("x86_64", "amd64", "amd64")
        else -> null
    }

    /** Versi code-server yang dipasang untuk ABI perangkat ini. */
    fun codeServerVersionFor(): String {
        val arches = arches() ?: return Pins.CODE_SERVER_VERSION
        return if (arches.third == "armv7l") Pins.CODE_SERVER_VERSION_ARMV7 else Pins.CODE_SERVER_VERSION
    }

    /** Perintah lengkap code-server; path guest lama dipertahankan persis. */
    fun prootCommand(ctx: Context): List<String> {
        val cmd = mutableListOf(
            prootBin(ctx).path,
            "-r", rootfsDir(ctx).path,
            "-0",
            // Android melarang hard link (link() -> EACCES) di penyimpanan aplikasi.
            // dpkg membuat var/lib/dpkg/status-old lewat link() => "error creating new backup
            // file ... Permission denied". --link2symlink meniru hard link dengan symlink.
            "--link2symlink",
            "--kill-on-exit", // jangan tinggalkan proses yatim (port 8080 tetap terpakai)
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/dev/urandom:/dev/random"
        )
        // /dev/shm (dibutuhkan Python multiprocessing, Chromium, dll.; tidak ada di Android).
        val shm = shmDir(ctx)
        shm.mkdirs()
        try { android.system.Os.chmod(shm.path, 0x3FF) } catch (_: Exception) { }
        cmd += listOf("-b", "${shm.path}:/dev/shm")
        // Berkas /proc yang diblokir Android (stat, loadavg, ...) diganti versi palsu.
        for ((guest, host) in fakeProcBinds(ctx)) cmd += listOf("-b", "${host.path}:$guest")
        // Ikat penyimpanan bersama bila dapat dibaca (opsional, tanpa crash bila tidak).
        // Cukup exists(): canRead() bernilai false sebelum izin diberikan sehingga bind
        // terlewat selamanya. Bind direktori yang belum bisa dibaca aman bagi proot.
        if (File("/sdcard").exists()) {
            cmd += listOf("-b", "/sdcard:/sdcard")
        }
        if (File("/storage").exists()) {
            cmd += listOf("-b", "/storage:/storage")
        }
        cmd += listOf("/bin/bash", "/root/.vscmob/start.sh")
        return cmd
    }

    /**
     * Environment untuk proses proot.
     * CATATAN: LD_LIBRARY_PATH (host) dibutuhkan proot menemukan libtalloc;
     * skrip guest (start.sh) langsung meng-unset-nya agar tidak bocor ke node.
     */
    fun prootEnv(ctx: Context): Map<String, String> {
        ensureRuntimeDirs(ctx)
        return buildEnv(ctx)
    }

    /**
     * proot mengekstrak loader-nya ke PROOT_TMP_DIR; bila direktori ini tidak ada,
     * muncul "can't chmod .../tmp/proot-XXXX: No such file" lalu execve gagal
     * ("Permission denied"). Selalu pastikan ada sebelum start.
     */
    fun ensureRuntimeDirs(ctx: Context) {
        for (d in listOf(tmpDir(ctx), logsDir(ctx))) {
            d.mkdirs()
            try { android.system.Os.chmod(d.path, 0x1C0) } catch (_: Exception) { } // 0700
        }
        for (rel in listOf("usr/bin/bash", "bin/bash")) {
            val b = File(rootfsDir(ctx), rel)
            if (b.isFile && !java.nio.file.Files.isSymbolicLink(b.toPath())) {
                try { android.system.Os.chmod(b.path, 0x1ED) } catch (_: Exception) { } // 0755
            }
        }
        // /tmp di dalam rootfs juga harus ada & writable (1777).
        val gtmp = File(rootfsDir(ctx), "tmp")
        gtmp.mkdirs()
        try { android.system.Os.chmod(gtmp.path, 0x3FF) } catch (_: Exception) { } // 1777
    }

    private fun buildEnv(ctx: Context): Map<String, String> {
        val env = baseEnv(ctx).toMutableMap()
        val l = File(libDir(ctx), "proot-loader")
        if (l.exists()) env["PROOT_LOADER"] = l.path
        val l32 = File(libDir(ctx), "proot-loader32")
        if (l32.exists()) env["PROOT_LOADER_32"] = l32.path
        return env
    }

    private fun baseEnv(ctx: Context): Map<String, String> = mapOf(
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LD_LIBRARY_PATH" to libDir(ctx).path,
        "PROOT_TMP_DIR" to tmpDir(ctx).path,
        "TMPDIR" to tmpDir(ctx).path,
        "PROOT_NO_SECCOMP" to "1"
    )

    /** Tulis skrip start di dalam rootfs (idempoten). */
    fun writeStartScript(rootfs: File) {
        val dir = File(rootfs, "root/.vscmob")
        dir.mkdirs()
        val f = File(dir, "start.sh")
        f.writeText(
            listOf(
                "#!/bin/bash",
                "# Dibuat otomatis oleh CodeX Studio. Jangan dihapus.",
                "unset LD_LIBRARY_PATH",
                "unset PROOT_TMP_DIR",
                "unset PROOT_NO_SECCOMP",
                "unset TMPDIR",
                "export HOME=/root",
                "export USER=root",
                "export LOGNAME=root",
                "export SHELL=/bin/bash", // terminal terintegrasi memakai $SHELL
                "export LANG=C.UTF-8",
                "export TERM=xterm-256color",
                "export TMPDIR=/tmp",
                "export PATH=/opt/code-server/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "exec /opt/code-server/bin/code-server --config /root/.config/code-server/config.yaml"
            ).joinToString("\n") + "\n"
        )
        try { android.system.Os.chmod(f.path, 0x1ED) } catch (_: Exception) { } // 0755
    }

    /** Tulis config code-server (auth none secara default — server hanya di 127.0.0.1). */
    fun writeServerConfig(rootfs: File, authEnabled: Boolean, password: String) {
        val dir = File(rootfs, "root/.config/code-server")
        dir.mkdirs()
        val f = File(dir, "config.yaml")
        f.writeText(
            buildString {
                append("bind-addr: 127.0.0.1:").append(SERVER_PORT).append('\n')
                append("auth: ").append(if (authEnabled) "password" else "none").append('\n')
                if (authEnabled) append("password: ").append(password).append('\n')
                append("cert: false\n")
            }
        )
    }

    /** Hapus path apa adanya (symlink dihapus, bukan targetnya), lalu tulis ulang sebagai berkas biasa. */
    private fun replaceFile(f: File, text: String): Boolean = runCatching {
        f.parentFile?.mkdirs()
        Files.deleteIfExists(f.toPath())
        f.writeText(text)
        true
    }.getOrDefault(false)

    /** Tulis berkas hanya bila belum ada (jangan timpa perubahan pengguna). */
    private fun writeIfMissing(f: File, text: String) {
        runCatching {
            if (Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS) && f.exists()) return
            replaceFile(f, text)
        }
    }

    /**
     * Sinkronkan jaringan Android -> rootfs. Dipanggil sebelum tiap start.
     *
     * BUG LAMA: /etc/resolv.conf di image Debian adalah symlink (dangling ke
     * /run/systemd/resolve/...). File.exists() false untuk symlink rusak -> tidak dihapus,
     * writeText() menulis lewat symlink ke direktori yang tidak ada -> gagal diam-diam
     * (runCatching) -> resolv.conf "hilang". Sekarang symlink dihapus dulu.
     */
    fun syncNetworkFiles(ctx: Context, rootfs: File) {
        val etc = File(rootfs, "etc")
        etc.mkdirs()

        val host = "vscode-mobile"
        replaceFile(File(etc, "hostname"), "$host\n")
        replaceFile(
            File(etc, "hosts"),
            "127.0.0.1 localhost localhost.localdomain $host\n" +
                "::1 localhost ip6-localhost ip6-loopback\n"
        )

        val dns = mutableListOf<String>()
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Jaringan aktif dulu, lalu sisanya.
            val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
            for (network in nets) {
                val lp = cm.getLinkProperties(network) ?: continue
                for (addr in lp.dnsServers) {
                    val a = addr.hostAddress ?: continue
                    if (a.contains('%')) continue // IPv6 link-local ber-scope tak valid di resolv.conf
                    if (a !in dns) dns.add(a)
                }
            }
        }
        // Selalu sertakan DNS publik sebagai cadangan (DNS Android bisa tak terjangkau dari proot).
        for (fb in listOf("1.1.1.1", "8.8.8.8")) if (fb !in dns) dns.add(fb)
        val resolvText = dns.take(5).joinToString("") { "nameserver $it\n" } +
            "options timeout:2 attempts:2\n"
        if (!replaceFile(File(etc, "resolv.conf"), resolvText)) {
            // Jalur cadangan: tulis lewat shell-less fallback ke /etc/resolv.conf.vscmob lalu rename.
            runCatching {
                val tmp = File(etc, "resolv.conf.vscmob")
                tmp.writeText(resolvText)
                Files.move(tmp.toPath(), File(etc, "resolv.conf").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        runCatching {
            val mtab = File(etc, "mtab")
            if (!Files.exists(mtab.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(mtab.toPath(), Paths.get("/proc/mounts"))
            }
        }
    }

    /**
     * Siapkan isi guest agar apt/dpkg/terminal berjalan tanpa tweak manual (idempoten).
     */
    fun prepareGuest(rootfs: File) {
        // Titik mount & direktori standar yang kadang tidak ada di tarball.
        for (d in listOf("dev", "dev/shm", "proc", "sys", "run", "run/lock", "var/tmp", "var/lib/dpkg",
            "var/cache/apt/archives/partial", "var/lib/apt/lists/partial", "root", "sdcard", "storage",
            "etc/apt/apt.conf.d", "etc/dpkg/dpkg.cfg.d", "etc/profile.d", "opt", "usr/local/bin")) {
            runCatching { File(rootfs, d).mkdirs() }
        }
        runCatching { android.system.Os.chmod(File(rootfs, "var/tmp").path, 0x3FF) } // 1777
        runCatching { android.system.Os.chmod(File(rootfs, "dev/shm").path, 0x3FF) }

        // apt: sandbox user _apt butuh setgroups/seteuid yang tidak ada di proot -> jalan sebagai root.
        replaceFile(
            File(rootfs, "etc/apt/apt.conf.d/99vscmob"),
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n"
        )
        // dpkg: tanpa fsync berlebihan (lebih cepat & aman di filesystem Android).
        replaceFile(
            File(rootfs, "etc/dpkg/dpkg.cfg.d/99vscmob"),
            "force-unsafe-io\nno-debsig\n"
        )
        // Login shell (terminal): PATH code-server + variabel dasar.
        replaceFile(
            File(rootfs, "etc/profile.d/99-vscmob.sh"),
            "export PATH=/opt/code-server/bin:\$PATH\nexport SHELL=/bin/bash\n"
        )
        // Pastikan akun root ada (proot -0 butuh entri untuk nama pengguna & HOME).
        writeIfMissing(File(rootfs, "etc/passwd"),
            "root:x:0:0:root:/root:/bin/bash\nnobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n")
        writeIfMissing(File(rootfs, "etc/group"), "root:x:0:\nnogroup:x:65534:\n")
        writeIfMissing(File(rootfs, "root/.bashrc"),
            "# CodeX Studio\nexport PATH=/opt/code-server/bin:\$PATH\nalias ll='ls -alF'\n")
    }

    /**
     * Android memblokir sebagian /proc (stat, loadavg, vmstat, ... -> EACCES) sehingga
     * free/top/uptime/ps dan beberapa alat Node gagal. Ganti dengan berkas palsu hanya bila
     * aslinya tidak bisa dibaca. Mengembalikan pasangan (path guest, berkas host).
     */
    fun fakeProcBinds(ctx: Context): List<Pair<String, File>> {
        val dir = fakeProcDir(ctx)
        dir.mkdirs()
        fun readable(p: String) = runCatching { File(p).inputStream().use { it.read() }; true }.getOrDefault(false)
        val up = android.os.SystemClock.elapsedRealtime() / 1000
        val boot = System.currentTimeMillis() / 1000 - up
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val statText = buildString {
            append("cpu  ${up * 50} 0 ${up * 20} ${up * 400} 0 0 0 0 0 0\n")
            for (i in 0 until cpus) append("cpu$i ${up * 50 / cpus} 0 ${up * 20 / cpus} ${up * 400 / cpus} 0 0 0 0 0 0\n")
            append("intr 0\nctxt 0\nbtime $boot\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        }
        val defs = listOf(
            Triple("/proc/stat", "stat", statText),
            Triple("/proc/loadavg", "loadavg", "0.10 0.10 0.10 1/100 1\n"),
            Triple("/proc/uptime", "uptime", "$up.00 ${up * cpus}.00\n"),
            Triple("/proc/version", "version",
                "Linux version 6.2.1-vscmob (proot@android) (gcc 12.2.0) #1 SMP PREEMPT\n"),
            Triple("/proc/vmstat", "vmstat", "nr_free_pages 100000\nnr_inactive_anon 0\nnr_active_anon 0\n"),
            Triple("/proc/sys/kernel/cap_last_cap", "cap_last_cap", "40\n")
        )
        val out = mutableListOf<Pair<String, File>>()
        for ((guest, name, text) in defs) {
            if (readable(guest)) continue
            val f = File(dir, name)
            runCatching { f.writeText(text) }
            if (f.exists()) out.add(guest to f)
        }
        return out
    }
}
