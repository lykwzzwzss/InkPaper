package com.inkpaper.epd.nfc

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import com.inkpaper.epd.BuildConfig
import rikka.shizuku.Shizuku

/** Manages binding to `ShizukuBridgeService` for non-root shell-level Q-Poll calls. */
object ShizukuChannel {
    const val UNAVAILABLE = Int.MIN_VALUE

    @Volatile
    private var bridgeBinder: IBinder? = null

    @Volatile
    private var binding = false

    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            bridgeBinder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            bridgeBinder = null
        }
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int = 28) {
        runCatching {
            if (isBinderAlive() && !hasPermission()) {
                Shizuku.requestPermission(requestCode)
            }
        }
    }

    fun available(): Boolean {
        val binder = bridgeBinder
        return binder != null && binder.isBinderAlive
    }

    fun bind(context: Context) {
        if (available() || binding || !hasPermission()) return
        val args = userServiceArgs ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuBridgeService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_nfc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .also { userServiceArgs = it }

        binding = true
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { binding = false }
    }

    fun unbind() {
        val args = userServiceArgs ?: return
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        bridgeBinder = null
        binding = false
    }

    /** Blocks up to `timeoutMs` on a worker thread until the Shizuku service connects. */
    fun ensureConnected(context: Context, timeoutMs: Long = 3500L): Boolean {
        if (available()) return true
        if (!hasPermission()) return false
        bind(context)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!available() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        return available()
    }

    fun sendQtag(action: Int): Int = transact(ShizukuBridgeService.TRANSACT_QTAG, action)

    fun resumePolling(): Int = transact(ShizukuBridgeService.TRANSACT_RESUME_POLLING)

    private fun transact(code: Int, vararg args: Int): Int {
        val binder = bridgeBinder
        if (binder == null || !binder.isBinderAlive) return UNAVAILABLE
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuBridgeService.INTERFACE_DESCRIPTOR)
            for (arg in args) data.writeInt(arg)
            if (!binder.transact(code, data, reply, 0)) return UNAVAILABLE
            reply.readException()
            reply.readInt()
        } catch (_: Throwable) {
            UNAVAILABLE
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
