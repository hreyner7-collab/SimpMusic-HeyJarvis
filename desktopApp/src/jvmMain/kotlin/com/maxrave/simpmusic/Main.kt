package com.maxrave.simpmusic

import com.maxrave.simpmusic.jerry.DesktopJarvis
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin entry point for SimpMusic Desktop.
 *
 * All window setup, Sentry init, Koin loading, deep link
 * handling, mini-player wiring, and tray integration live in
 * `composeApp/src/jvmMain/.../main.kt` as `fun runDesktopApp()`. That keeps
 * the shared module self-contained (it can still be launched directly
 * during development) while letting this :desktopApp module own the JVM
 * launcher class Conveyor / compose.desktop point `mainClass` at.
 *
 * The :composeApp KMP library exposes runDesktopApp() publicly so this
 * stub can delegate without duplicating any window-construction logic.
 */
/**
 * Force the X11 WM_CLASS so the Linux taskbar/dock shows "SimpMusic" instead of
 * the class name of whatever thread first touches AWT — with the Conveyor native
 * launcher that ends up being a Kotlin coroutine worker
 * (kotlinx.coroutines...CoroutineScheduler$Worker, hence the wrong dock label).
 *
 * JDK derives WM_CLASS from sun.awt.X11.XToolkit.awtAppClassName, read lazily at
 * window-init time, and there is no supported system property to set it (see the
 * still-open RFE JDK-6528430). So we overwrite the field by reflection AFTER the
 * toolkit is constructed but BEFORE the first window opens. Because we set it
 * post-construction, the value is used verbatim (no '.'->'-' mangling).
 *
 * Requires `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED` (set for Linux in
 * conveyor.conf). The value MUST match StartupWMClass in the generated .desktop
 * file, else GNOME/KDE won't bind the launcher icon to the window.
 */
private fun forceLinuxWmClass(appName: String = "SimpMusic") {
    if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return
    runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        // Only the X11/XWayland toolkit exposes awtAppClassName; skip WLToolkit/headless.
        if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply {
            isAccessible = true
            set(null, appName)
        }
    }.onFailure { System.err.println("forceLinuxWmClass failed: ${it.message}") }
}

/**
 * Windows-only CPU affinity and working-set governor using JNA (`kernel32` / `psapi`).
 *
 * Pins the JVM process (and its jpackage launcher parent, if present) to 2 logical processors
 * (`mask = 3L`: CPU 0 and CPU 1), sets a 32 MB..240 MB working-set cap, and trims cold pages
 * back to the Windows Standby List without touching C heap locks or stalling audio/UI.
 */
private object WindowsProcessOptimizer {
    private const val TARGET_CPU_AFFINITY_MASK = 3L // CPU 0 + CPU 1
    private const val MIN_WORKING_SET_BYTES = 32L * 1024 * 1024
    private const val MAX_WORKING_SET_BYTES = 240L * 1024 * 1024
    private const val TRIM_THRESHOLD_BYTES = 245L * 1024 * 1024
    private const val PARENT_MIN_WS_BYTES = 1L * 1024 * 1024
    private const val PARENT_MAX_WS_BYTES = 16L * 1024 * 1024

    private const val QUOTA_LIMITS_HARDWS_MIN_DISABLE = 0x00000002
    private const val QUOTA_LIMITS_HARDWS_MAX_ENABLE = 0x00000004

    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_SET_INFORMATION = 0x0200
    private const val PROCESS_QUERY_INFORMATION = 0x0400

    private const val WATCHDOG_INTERVAL_MS = 3_000L
    private const val COMMAND_TRIM_COOLDOWN_MS = 5_000L

    private interface WinKernel32 : Library {
        fun GetCurrentProcess(): Pointer

        fun OpenProcess(
            dwDesiredAccess: Int,
            bInheritHandle: Boolean,
            dwProcessId: Int,
        ): Pointer?

        fun CloseHandle(hObject: Pointer): Boolean

        fun SetProcessAffinityMask(
            hProcess: Pointer,
            dwProcessAffinityMask: Long,
        ): Boolean

        fun SetProcessWorkingSetSizeEx(
            hProcess: Pointer,
            dwMinimumWorkingSetSize: Long,
            dwMaximumWorkingSetSize: Long,
            flags: Int,
        ): Boolean

        fun SetProcessWorkingSetSize(
            hProcess: Pointer,
            dwMinimumWorkingSetSize: Long,
            dwMaximumWorkingSetSize: Long,
        ): Boolean

        fun K32EmptyWorkingSet(hProcess: Pointer): Boolean

        fun K32GetProcessMemoryInfo(
            hProcess: Pointer,
            ppsmemCounters: Pointer,
            cb: Int,
        ): Boolean
    }

    private interface WinPsapi : Library {
        fun EmptyWorkingSet(hProcess: Pointer): Boolean

        fun GetProcessMemoryInfo(
            hProcess: Pointer,
            ppsmemCounters: Pointer,
            cb: Int,
        ): Boolean
    }

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    private val kernel32: WinKernel32? by lazy {
        if (!isWindows) null else runCatching { Native.load("kernel32", WinKernel32::class.java) }.getOrNull()
    }

    private val psapi: WinPsapi? by lazy {
        if (!isWindows) null else runCatching { Native.load("psapi", WinPsapi::class.java) }.getOrNull()
    }

    private val lastTrimAtMs = AtomicLong(0L)
    @Volatile private var installed = false

    fun install() {
        if (!isWindows || installed) return
        val k32 = kernel32 ?: return
        installed = true

        val currentProc = k32.GetCurrentProcess()
        configureProcess(k32, currentProc, MIN_WORKING_SET_BYTES, MAX_WORKING_SET_BYTES)
        applyToParentLauncherIfSimpMusic(k32)

        DesktopJarvis.onMemoryTrimRequested = { reason ->
            trimWorkingSet(reason)
        }

        Thread({
            val countersSize = if (Native.POINTER_SIZE == 8) 72 else 40
            val counters = Memory(countersSize.toLong())
            val wsOffset = if (Native.POINTER_SIZE == 8) 16L else 12L
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val now = System.currentTimeMillis()
                val wsBytes = queryWorkingSetBytes(k32, currentProc, counters, countersSize, wsOffset)
                if (wsBytes > TRIM_THRESHOLD_BYTES) {
                    val prev = lastTrimAtMs.get()
                    if (now - prev >= WATCHDOG_INTERVAL_MS && lastTrimAtMs.compareAndSet(prev, now)) {
                        emptyWorkingSet(k32, currentProc)
                    }
                }
            }
        }, "win-ws-trimmer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun trimWorkingSet(reason: String) {
        if (!isWindows) return
        val k32 = kernel32 ?: return
        val now = System.currentTimeMillis()
        if (reason != "models-ready") {
            val prev = lastTrimAtMs.get()
            if (prev != 0L && now - prev < COMMAND_TRIM_COOLDOWN_MS) return
            if (!lastTrimAtMs.compareAndSet(prev, now)) return
        } else {
            lastTrimAtMs.set(now)
        }
        emptyWorkingSet(k32, k32.GetCurrentProcess())
        if (reason == "models-ready") {
            applyToParentLauncherIfSimpMusic(k32)
        }
    }

    private fun configureProcess(
        k32: WinKernel32,
        hProc: Pointer,
        minWsBytes: Long,
        maxWsBytes: Long,
    ) {
        runCatching {
            k32.SetProcessAffinityMask(hProc, TARGET_CPU_AFFINITY_MASK)
        }
        runCatching {
            val appliedHard =
                k32.SetProcessWorkingSetSizeEx(hProc, minWsBytes, maxWsBytes, QUOTA_LIMITS_HARDWS_MAX_ENABLE) ||
                    k32.SetProcessWorkingSetSizeEx(
                        hProc,
                        minWsBytes,
                        maxWsBytes,
                        QUOTA_LIMITS_HARDWS_MAX_ENABLE or QUOTA_LIMITS_HARDWS_MIN_DISABLE,
                    )
            if (!appliedHard) {
                val appliedSoft = k32.SetProcessWorkingSetSizeEx(hProc, minWsBytes, maxWsBytes, 0)
                if (!appliedSoft) {
                    k32.SetProcessWorkingSetSize(hProc, minWsBytes, maxWsBytes)
                }
            }
        }
    }

    private fun applyToParentLauncherIfSimpMusic(k32: WinKernel32) {
        runCatching {
            val parent = ProcessHandle.current().parent().orElse(null) ?: return
            val cmd = parent.info().command().orElse("")
            if (!cmd.endsWith("SimpMusic.exe", ignoreCase = true)) return
            val hParent = k32.OpenProcess(
                PROCESS_SET_QUOTA or PROCESS_SET_INFORMATION or PROCESS_QUERY_INFORMATION,
                false,
                parent.pid().toInt(),
            ) ?: return
            try {
                configureProcess(k32, hParent, PARENT_MIN_WS_BYTES, PARENT_MAX_WS_BYTES)
                emptyWorkingSet(k32, hParent)
            } finally {
                k32.CloseHandle(hParent)
            }
        }
    }

    private fun emptyWorkingSet(
        k32: WinKernel32,
        hProc: Pointer,
    ) {
        runCatching {
            if (!k32.K32EmptyWorkingSet(hProc)) {
                psapi?.EmptyWorkingSet(hProc)
            }
        }
    }

    private fun queryWorkingSetBytes(
        k32: WinKernel32,
        hProc: Pointer,
        counters: Memory,
        countersSize: Int,
        wsOffset: Long,
    ): Long =
        runCatching {
            counters.clear()
            counters.setInt(0L, countersSize)
            val ok =
                k32.K32GetProcessMemoryInfo(hProc, counters, countersSize) ||
                    (psapi?.GetProcessMemoryInfo(hProc, counters, countersSize) == true)
            if (ok) {
                if (Native.POINTER_SIZE == 8) counters.getLong(wsOffset) else counters.getInt(wsOffset).toLong() and 0xffffffffL
            } else {
                -1L
            }
        }.getOrDefault(-1L)
}

fun main(args: Array<String>) {
    WindowsProcessOptimizer.install()
    forceLinuxWmClass()
    runDesktopApp(args)
}
