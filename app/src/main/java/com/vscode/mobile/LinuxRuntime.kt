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

    /** Perintah lengkap untuk menjalankan code-server di dalam Debian via proot. */
    fun prootCommand(ctx: Context): List<String> {
        val cmd = mutableListOf(
            prootBin(ctx).path,
            "-r", rootfsDir(ctx).path,
            "-0",
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/dev/urandom:/dev/random"
        )
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
                "# Dibuat otomatis oleh VS Code Mobile. Jangan dihapus.",
                "unset LD_LIBRARY_PATH",
                "unset PROOT_TMP_DIR",
                "unset PROOT_NO_SECCOMP",
                "unset TMPDIR",
                "export HOME=/root",
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

    /**
     * Sinkronkan /etc/resolv.conf & /etc/hosts dari Android ke rootfs
     * (dipanggil sebelum tiap start — DNS bisa berubah antar jaringan).
     */
    fun syncNetworkFiles(ctx: Context, rootfs: File) {
        val etc = File(rootfs, "etc")
        etc.mkdirs()

        runCatching {
            val hosts = File(etc, "hosts")
            if (!Files.isSymbolicLink(hosts.toPath())) {
                hosts.writeText("127.0.0.1 localhost localhost.localdomain\n")
            }
        }

        val dns = mutableListOf<String>()
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in cm.allNetworks) {
                val lp = cm.getLinkProperties(network) ?: continue
                for (addr in lp.dnsServers) {
                    addr.hostAddress?.let { if (it !in dns) dns.add(it) }
                }
            }
        }
        if (dns.isEmpty()) {
            dns.add("8.8.8.8")
            dns.add("1.1.1.1")
        }
        runCatching {
            val resolv = File(etc, "resolv.conf")
            if (resolv.exists()) resolv.delete()
            resolv.writeText(
                dns.joinToString("") { "nameserver $it\n" } +
                    "options timeout:2 attempts:2\n"
            )
        }

        runCatching {
            val mtab = File(etc, "mtab")
            if (!Files.exists(mtab.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(mtab.toPath(), Paths.get("/proc/mounts"))
            }
        }
    }
}
