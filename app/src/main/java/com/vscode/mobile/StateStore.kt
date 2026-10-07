package com.vscode.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Status aplikasi yang dipersistenkan di files/linux/.state.json.
 * Berkas ini HANYA ditulis setelah instalasi selesai atomik, sehingga
 * keberadaannya == "instalasi valid".
 */
data class AppState(
    val installed: Boolean = false,
    val codeServerVersion: String = "",
    val rootfsSource: String = "",
    val authEnabled: Boolean = false,
    val password: String = "",
    val keepScreenOn: Boolean = false,
    val createdAt: Long = 0L
)

object StateStore {

    private fun file(ctx: Context): File = File(linuxDir(ctx), ".state.json")

    fun linuxDir(ctx: Context): File = File(ctx.filesDir, "linux")

    fun read(ctx: Context): AppState {
        val f = file(ctx)
        if (!f.exists()) return AppState()
        return try {
            val o = JSONObject(f.readText())
            AppState(
                installed = o.optBoolean("installed", false),
                codeServerVersion = o.optString("codeServerVersion", ""),
                rootfsSource = o.optString("rootfsSource", ""),
                authEnabled = o.optBoolean("authEnabled", false),
                password = o.optString("password", ""),
                keepScreenOn = o.optBoolean("keepScreenOn", false),
                createdAt = o.optLong("createdAt", 0L)
            )
        } catch (_: Exception) {
            AppState()
        }
    }

    /** Tulis state ke direktori linux (atau staging saat instalasi). */
    fun writeTo(dir: File, state: AppState) {
        val f = File(dir, ".state.json")
        f.parentFile?.mkdirs()
        val o = JSONObject()
        o.put("installed", state.installed)
        o.put("codeServerVersion", state.codeServerVersion)
        o.put("rootfsSource", state.rootfsSource)
        o.put("authEnabled", state.authEnabled)
        o.put("password", state.password)
        o.put("keepScreenOn", state.keepScreenOn)
        o.put("createdAt", state.createdAt)
        val tmp = File(f.parentFile, ".state.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(), f.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun write(ctx: Context, state: AppState) = writeTo(linuxDir(ctx), state)

    /** Hapus seluruh direktori linux (uninstall data). */
    fun wipe(ctx: Context) {
        deleteRecursive(linuxDir(ctx))
    }

    /** Kata sandi acak yang mudah diketik di ponsel (tanpa karakter mirip). */
    fun newPassword(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray()
        val rnd = SecureRandom()
        return buildString { repeat(12) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
    }

    /** Hapus rekursif yang aman terhadap symlink (tidak mengikuti tautan). */
    fun deleteRecursive(f: File) {
        try {
            if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
                f.listFiles()?.forEach { deleteRecursive(it) }
            }
            f.delete()
        } catch (_: Exception) {
            // abaikan — best effort
        }
    }
}
