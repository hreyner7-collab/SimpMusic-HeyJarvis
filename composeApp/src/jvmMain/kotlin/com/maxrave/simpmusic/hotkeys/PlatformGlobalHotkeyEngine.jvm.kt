package com.maxrave.simpmusic.hotkeys

import com.maxrave.logger.Logger
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PlatformGlobalHotkeyJvm"
private const val WM_QUIT = 0x0012
private const val WM_HOTKEY = 0x0312
private const val PM_NOREMOVE = 0x0000

/**
 * Win32 `MSG` structure mapped via core JNA (`net.java.dev.jna:jna`, no `jna-platform` required).
 * Pointer-sized fields (`hwnd`, `wParam`, `lParam`) match both 64-bit and 32-bit Windows ABI.
 */
@Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "ptX", "ptY")
open class Win32HotkeyMsg : Structure() {
    @JvmField var hwnd: Pointer? = null

    @JvmField var message: Int = 0

    @JvmField var wParam: Pointer? = null

    @JvmField var lParam: Pointer? = null

    @JvmField var time: Int = 0

    @JvmField var ptX: Int = 0

    @JvmField var ptY: Int = 0
}

internal interface Win32Kernel32 : StdCallLibrary {
    fun GetCurrentThreadId(): Int
}

internal interface Win32User32 : StdCallLibrary {
    fun RegisterHotKey(
        hWnd: Pointer?,
        id: Int,
        fsModifiers: Int,
        vk: Int,
    ): Boolean

    fun UnregisterHotKey(
        hWnd: Pointer?,
        id: Int,
    ): Boolean

    fun PeekMessageW(
        lpMsg: Win32HotkeyMsg,
        hWnd: Pointer?,
        wMsgFilterMin: Int,
        wMsgFilterMax: Int,
        wRemoveMsg: Int,
    ): Boolean

    fun GetMessageW(
        lpMsg: Win32HotkeyMsg,
        hWnd: Pointer?,
        wMsgFilterMin: Int,
        wMsgFilterMax: Int,
    ): Int

    fun PostThreadMessageW(
        idThread: Int,
        msg: Int,
        wParam: Pointer?,
        lParam: Pointer?,
    ): Boolean
}

actual class PlatformGlobalHotkeyEngine actual constructor() {
    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

    actual val isSupportedOnCurrentPlatform: Boolean
        get() = isWindows

    private val kernel32: Win32Kernel32? by lazy {
        if (!isWindows) return@lazy null
        runCatching { Native.load("kernel32", Win32Kernel32::class.java) }
            .onFailure { Logger.w(TAG, "Failed to load kernel32 via JNA: ${it.message}") }
            .getOrNull()
    }

    private val user32: Win32User32? by lazy {
        if (!isWindows) return@lazy null
        runCatching { Native.load("user32", Win32User32::class.java) }
            .onFailure { Logger.w(TAG, "Failed to load user32 via JNA: ${it.message}") }
            .getOrNull()
    }

    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val nativeThreadId = AtomicInteger(0)
    private var messageThread: Thread? = null

    actual fun start(
        bindings: List<HotkeyBinding>,
        onAction: (HotkeyAction) -> Unit,
    ): Set<HotkeyAction> {
        synchronized(lifecycleLock) {
            stopLocked()
            if (!isWindows) return emptySet()
            val u32 = user32 ?: return emptySet()
            val k32 = kernel32 ?: return emptySet()

            val activeBindings =
                bindings
                    .filter { it.enabled }
                    .distinctBy { (it.modifiers and HotkeyModifiers.USER_MODIFIER_MASK) to it.virtualKey }
            if (activeBindings.isEmpty()) return emptySet()

            val readyLatch = CountDownLatch(1)
            val registeredActions = LinkedHashSet<HotkeyAction>()
            running.set(true)

            val worker =
                Thread(
                    {
                        val tid = runCatching { k32.GetCurrentThreadId() }.getOrDefault(0)
                        nativeThreadId.set(tid)
                        val msg = Win32HotkeyMsg()
                        // Force creation of this thread's Win32 message queue before releasing readyLatch.
                        runCatching { u32.PeekMessageW(msg, null, 0, 0, PM_NOREMOVE) }

                        val idToAction = HashMap<Int, HotkeyAction>()
                        var nextId = 1
                        for (binding in activeBindings) {
                            val id = nextId++
                            val baseMods = binding.modifiers and HotkeyModifiers.USER_MODIFIER_MASK
                            val allowRepeat =
                                binding.action == HotkeyAction.VOLUME_UP ||
                                    binding.action == HotkeyAction.VOLUME_DOWN
                            val winMods =
                                if (allowRepeat) {
                                    baseMods
                                } else {
                                    baseMods or HotkeyModifiers.MOD_NOREPEAT
                                }
                            val ok =
                                runCatching {
                                    u32.RegisterHotKey(null, id, winMods, binding.virtualKey)
                                }.getOrDefault(false)
                            if (ok) {
                                idToAction[id] = binding.action
                                registeredActions.add(binding.action)
                            } else {
                                Logger.w(
                                    TAG,
                                    "Could not register global hotkey ${binding.displayLabel} for ${binding.action} (may be in use by another app)",
                                )
                            }
                        }

                        readyLatch.countDown()

                        try {
                            if (idToAction.isEmpty()) return@Thread
                            // Blocks inside kernel wait with 0% CPU until WM_HOTKEY or WM_QUIT arrives.
                            while (running.get()) {
                                val res = u32.GetMessageW(msg, null, 0, 0)
                                if (res <= 0) break // 0 = WM_QUIT, -1 = error
                                if (msg.message == WM_HOTKEY) {
                                    val hotkeyId = Pointer.nativeValue(msg.wParam).toInt()
                                    val action = idToAction[hotkeyId]
                                    if (action != null) {
                                        runCatching { onAction(action) }
                                            .onFailure { Logger.w(TAG, "Hotkey handler threw for $action: ${it.message}") }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Logger.w(TAG, "Win32 hotkey message loop terminated: ${e.message}")
                        } finally {
                            idToAction.keys.forEach { id ->
                                runCatching { u32.UnregisterHotKey(null, id) }
                            }
                            nativeThreadId.compareAndSet(tid, 0)
                        }
                    },
                    "SimpMusic-WinHotkeys",
                ).apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }

            messageThread = worker
            worker.start()
            runCatching { readyLatch.await(1_500L, TimeUnit.MILLISECONDS) }
            return registeredActions.toSet()
        }
    }

    actual fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        running.set(false)
        val tid = nativeThreadId.getAndSet(0)
        if (tid != 0) {
            runCatching {
                user32?.PostThreadMessageW(tid, WM_QUIT, null, null)
            }
        }
        val thread = messageThread
        messageThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(600L) }
        }
    }
}
