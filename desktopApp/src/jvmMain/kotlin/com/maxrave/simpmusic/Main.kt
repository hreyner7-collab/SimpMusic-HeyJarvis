package com.maxrave.simpmusic

import com.maxrave.simpmusic.utils.DEFAULT_TWO_CORE_AFFINITY_MASK
import com.maxrave.simpmusic.utils.selectTwoCoreMask
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Toolkit

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
 * Windows-only 2-core CPU affinity governor using JNA (`kernel32`).
 *
 * Pins the JVM process (and its jpackage launcher parent, if present) to 2 available logical
 * processors via `GetProcessAffinityMask` + `SetProcessAffinityMask` (default `0x3L`: CPU 0 and
 * CPU 1, or the first 2 set bits of the system mask).
 *
 * Intentionally applies ZERO hard working-set RAM cap and ZERO `EmptyWorkingSet` calls so hot
 * JVM/Skia/voice-model pages never incur page-fault stalls, and spawns no background polling thread.
 */
internal object WindowsProcessOptimizer {
    private const val PROCESS_SET_INFORMATION = 0x0200
    private const val PROCESS_QUERY_INFORMATION = 0x0400

    private interface WinKernel32 : Library {
        fun GetCurrentProcess(): Pointer

        fun OpenProcess(
            dwDesiredAccess: Int,
            bInheritHandle: Boolean,
            dwProcessId: Int,
        ): Pointer?

        fun CloseHandle(hObject: Pointer): Boolean

        fun GetProcessAffinityMask(
            hProcess: Pointer,
            lpProcessAffinityMask: Pointer,
            lpSystemAffinityMask: Pointer,
        ): Boolean

        fun SetProcessAffinityMask(
            hProcess: Pointer,
            dwProcessAffinityMask: Long,
        ): Boolean
    }

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    private val kernel32: WinKernel32? by lazy {
        if (!isWindows) null else runCatching { Native.load("kernel32", WinKernel32::class.java) }.getOrNull()
    }

    @Volatile private var installed = false

    internal fun computeTwoCoreMask(systemMask: Long): Long = selectTwoCoreMask(systemMask)

    fun install() {
        if (!isWindows || installed) return
        val k32 = kernel32 ?: return
        installed = true

        val currentProc = k32.GetCurrentProcess()
        configureProcess(k32, currentProc)
        applyToParentLauncherIfSimpMusic(k32)
    }

    private fun configureProcess(
        k32: WinKernel32,
        hProc: Pointer,
    ) {
        runCatching {
            val ptrSize = Native.POINTER_SIZE.toLong()
            val procMaskBuf = Memory(ptrSize).apply { clear() }
            val sysMaskBuf = Memory(ptrSize).apply { clear() }
            val systemMask =
                if (k32.GetProcessAffinityMask(hProc, procMaskBuf, sysMaskBuf)) {
                    if (Native.POINTER_SIZE == 8) {
                        sysMaskBuf.getLong(0L)
                    } else {
                        sysMaskBuf.getInt(0L).toLong() and 0xffffffffL
                    }
                } else {
                    DEFAULT_TWO_CORE_AFFINITY_MASK
                }
            val targetMask = computeTwoCoreMask(systemMask)
            if (targetMask != 0L) {
                k32.SetProcessAffinityMask(hProc, targetMask)
            }
        }
    }

    private fun applyToParentLauncherIfSimpMusic(k32: WinKernel32) {
        runCatching {
            val parent = ProcessHandle.current().parent().orElse(null) ?: return
            val cmd = parent.info().command().orElse("")
            if (!cmd.endsWith("SimpMusic.exe", ignoreCase = true)) return
            val hParent =
                k32.OpenProcess(
                    PROCESS_SET_INFORMATION or PROCESS_QUERY_INFORMATION,
                    false,
                    parent.pid().toInt(),
                ) ?: return
            try {
                configureProcess(k32, hParent)
            } finally {
                k32.CloseHandle(hParent)
            }
        }
    }
}

private fun applyPreferredRenderer() {
    if (System.getProperty("skiko.renderApi") != null) return
    val conf = java.io.File(java.io.File(System.getProperty("user.home"), ".simpmusic"), "renderer.conf")
    val api =
        runCatching {
            if (conf.isFile) conf.readText().trim().uppercase() else ""
        }.getOrDefault("")
    if (api in setOf("DIRECT3D", "OPENGL", "SOFTWARE", "METAL")) {
        System.setProperty("skiko.renderApi", api)
    } else if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
        System.setProperty("skiko.renderApi", "DIRECT3D")
    }
}

fun main(args: Array<String>) {
    applyPreferredRenderer()
    WindowsProcessOptimizer.install()
    forceLinuxWmClass()
    runDesktopApp(args)
}
