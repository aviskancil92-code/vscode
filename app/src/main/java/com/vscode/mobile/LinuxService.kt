package com.vscode.mobile

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

sealed class ServerState {
    object Idle : ServerState()
    object Starting : ServerState()
    data class Running(val url: String) : ServerState()
    data class Restarting(val attempt: Int) : ServerState()
    data class Error(val message: String) : ServerState()
    object Stopped : ServerState()
}

internal enum class ServerReadiness { READY, PROCESS_EXITED, STOP_REQUESTED, TIMED_OUT }

/** Small deterministic readiness gate; injected clock/sleep make timeout behavior testable. */
internal fun awaitServerReadiness(
    timeoutMs: Long,
    pollIntervalMs: Long,
    isAlive: () -> Boolean,
    shouldStop: () -> Boolean,
    probe: () -> Boolean,
    nowMs: () -> Long,
    sleep: (Long) -> Unit
): ServerReadiness {
    val deadline = nowMs() + timeoutMs.coerceAtLeast(0L)
    while (true) {
        if (shouldStop()) return ServerReadiness.STOP_REQUESTED
        if (!isAlive()) return ServerReadiness.PROCESS_EXITED
        if (probe()) return ServerReadiness.READY
        val remaining = deadline - nowMs()
        if (remaining <= 0L) return ServerReadiness.TIMED_OUT
        sleep(minOf(pollIntervalMs.coerceAtLeast(1L), remaining))
    }
}

/**
 * Foreground service yang menjalankan Debian (proot) + code-server di latar
 * belakang. Kebijakan restart otomatis dengan pembatasan badai-restart.
 */
class LinuxService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var serverProcess: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var loopActive = false
    private var restartCount = 0
    private var lastRestartAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Wajib panggil startForeground dulu bila masuk via startForegroundService.
                startForegroundCompat()
                stopRequested = true
                _state.value = ServerState.Stopped
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                startForegroundCompat()
                stopRequested = false
                _state.value = ServerState.Starting
                Thread { killTree() }.start()
                if (!loopActive) {
                    launchLoop()
                }
                return START_STICKY
            }
        }

        startForegroundCompat()

        if (!LinuxRuntime.isInstalled(this)) {
            _state.value = ServerState.Error("Linux belum terpasang — buka aplikasi untuk memasang.")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!loopActive) {
            launchLoop()
        }
        return START_STICKY
    }

    private fun launchLoop() {
        loopActive = true
        scope.launch {
            try {
                runLoop()
            } finally {
                loopActive = false
            }
        }
    }

    private suspend fun runLoop() {
        while (!stopRequested) {
            try {
                val logFile = File(LinuxRuntime.logsDir(this), "server.log")
                logFile.parentFile?.mkdirs()
                LinuxRuntime.prepareGuest(LinuxRuntime.rootfsDir(this))
                LinuxRuntime.syncNetworkFiles(this, LinuxRuntime.rootfsDir(this))
                LinuxRuntime.writeStartScript(LinuxRuntime.rootfsDir(this))
                _state.value = ServerState.Starting
                notify("Menyiapkan Linux & code-server…")

                val pb = ProcessBuilder(LinuxRuntime.prootCommand(this)).apply {
                    environment().clear()
                    environment().putAll(LinuxRuntime.prootEnv(this@LinuxService))
                    redirectErrorStream(true)
                    directory(filesDir)
                }
                val proc = pb.start()
                serverProcess = proc

                // Selalu drain stdout/stderr -> mencegah deadlock buffer pipe.
                val drainer = Thread {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                            for (line in lines) {
                                Log.i(TAG, line)
                                synchronized(logBuffer) {
                                    logBuffer.addLast(line)
                                    if (logBuffer.size > 400) logBuffer.removeFirst()
                                }
                                appendToFile(logFile, line)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                drainer.isDaemon = true
                drainer.start()

                val readiness = waitForServer(proc)
                if (readiness == ServerReadiness.READY) {
                    restartCount = 0
                    _state.value = ServerState.Running(LinuxRuntime.SERVER_URL)
                    notify("VS Code aktif · 127.0.0.1:${LinuxRuntime.SERVER_PORT}")
                } else if (readiness == ServerReadiness.TIMED_OUT && !stopRequested) {
                    Log.e(TAG, "code-server tidak ready dalam 120 detik; menghentikan proot sebelum retry")
                    notify("Server belum siap setelah 120 detik — mencoba ulang…")
                    killTree()
                    if (proc.isAlive) proc.destroyForcibly()
                }

                if (readiness == ServerReadiness.STOP_REQUESTED || stopRequested) break
                if (readiness == ServerReadiness.TIMED_OUT && !proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    _state.value = ServerState.Error("proot tidak berhenti setelah readiness timeout; lihat log")
                    notify("Proses server tidak dapat dihentikan — buka aplikasi untuk detail")
                    break
                }
                val exitCode = if (readiness == ServerReadiness.TIMED_OUT) proc.exitValue() else proc.waitFor()
                if (stopRequested) break

                val now = System.currentTimeMillis()
                if (now - lastRestartAt > 60_000) restartCount = 0
                lastRestartAt = now
                restartCount++

                if (restartCount > 5) {
                    _state.value = ServerState.Error(
                        "code-server berhenti berulang kali (kode keluar $exitCode). Buka menu → Lihat log."
                    )
                    notify("Kesalahan server — buka aplikasi untuk detail")
                    break
                }

                _state.value = ServerState.Restarting(restartCount)
                notify("Server mati (kode $exitCode) — memulai ulang…")
                delay(2_000L)
            } catch (e: Exception) {
                if (stopRequested) break
                Log.e(TAG, "gagal menjalankan proot", e)
                _state.value = ServerState.Error("Gagal menjalankan proot: ${e.message}")
                notify("Kesalahan: ${e.message}")
                break
            }
        }

        killTree()
    }

    /** Tunggu endpoint loopback ready, keluar, dihentikan pengguna, atau timeout 120 detik. */
    private fun waitForServer(proc: Process): ServerReadiness = awaitServerReadiness(
        timeoutMs = 120_000L,
        pollIntervalMs = 300L,
        isAlive = { proc.isAlive },
        shouldStop = { stopRequested },
        probe = ::probeServer,
        nowMs = { android.os.SystemClock.elapsedRealtime() },
        sleep = { Thread.sleep(it) }
    )

    private fun probeServer(): Boolean {
        return try {
            val conn = URL(LinuxRuntime.SERVER_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            try {
                val code = conn.responseCode
                code in 100..599
            } catch (_: Exception) {
                false
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Bunuh proot beserta seluruh proses guest (node) — dipilih lewat /proc
     * berdasarkan path exe/cmdline di bawah rootfs, aman tanpa API pid().
     */
    private fun killTree() {
        val proc = serverProcess
        serverProcess = null
        val rootfs = LinuxRuntime.rootfsDir(this).path
        val victims = collectGuestPids(rootfs)
        proc?.let { runCatching { it.destroy() } }
        try { Thread.sleep(150) } catch (_: InterruptedException) { }
        victims.forEach { pid -> runCatching { Os.kill(pid, 9) } }
        collectGuestPids(rootfs).forEach { pid -> runCatching { Os.kill(pid, 9) } }
    }

    private fun collectGuestPids(rootfs: String): List<Int> {
        val out = mutableListOf<Int>()
        val procs = File("/proc").listFiles { f -> Regex("^\\d+$").matches(f.name) } ?: return out
        val prootBin = LinuxRuntime.prootBin(this).path
        for (p in procs) {
            val pid = p.name.toInt()
            if (pid == android.os.Process.myPid()) continue
            val exe = runCatching {
                Files.readSymbolicLink(Paths.get("/proc/$pid/exe")).toString()
            }.getOrNull()
            val exeMatch = exe != null && (exe.startsWith(rootfs) || exe == prootBin)
            val cmdMatch = runCatching {
                String(File(p, "cmdline").readBytes()).replace('\u0000', ' ')
            }.getOrNull()?.contains(rootfs) == true
            if (exeMatch || cmdMatch) out.add(pid)
        }
        return out
    }

    @Synchronized
    private fun appendToFile(f: File, line: String) {
        try {
            if (f.length() > 2_000_000) {
                val old = File(f.path + ".1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- notifikasi

    private fun baseBuilder(): NotificationCompat.Builder {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("CodeX Studio")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
    }

    private fun startForegroundCompat() {
        val n = baseBuilder()
            .setContentText("Menyiapkan Linux…")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(text: String) {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, LinuxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = baseBuilder()
            .setContentText(text)
            .addAction(NotificationCompat.Action.Builder(0, "Hentikan", stopPi).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    override fun onDestroy() {
        stopRequested = true
        Thread { killTree() }.start()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LinuxService"
        private const val CHANNEL_ID = "vscode_server"
        private const val NOTIF_ID = 42

        const val ACTION_STOP = "com.vscode.mobile.action.STOP"
        const val ACTION_RESTART = "com.vscode.mobile.action.RESTART"

        private val _state = MutableStateFlow<ServerState>(ServerState.Idle)
        val state: StateFlow<ServerState> = _state

        private val logBuffer = ArrayDeque<String>()

        fun recentLogs(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

        fun start(ctx: Context) {
            val i = Intent(ctx, LinuxService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun requestRestart(ctx: Context) {
            val i = Intent(ctx, LinuxService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun requestStop(ctx: Context) {
            val i = Intent(ctx, LinuxService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

    }
}
