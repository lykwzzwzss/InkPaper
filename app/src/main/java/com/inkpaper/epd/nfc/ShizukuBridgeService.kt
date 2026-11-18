package com.inkpaper.epd.nfc

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.inkpaper.epd.BuildConfig
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Shizuku UserService running with shell/root identity to invoke vendor NCI Q-Poll
 * and `resumePolling` on devices where normal app processes lack `WRITE_SECURE_SETTINGS`.
 */
class ShizukuBridgeService : Binder() {
    private val appUid: Int
    private var cachedNfcService: Any? = null
    private var cachedNfcType: Class<*>? = null

    init {
        val uid = Process.myUid()
        if (uid != 0 && uid != 2000) {
            throw SecurityException("Shell/root privilege required (uid=$uid)")
        }
        appUid = resolveAppUid()
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TRANSACT_SHIZUKU_DESTROY) {
            System.exit(0)
            return true
        }
        if (code !in 4..6) return super.onTransact(code, data, reply, flags)
        if (getCallingUid() != appUid) throw SecurityException("Unauthorized caller")

        data.enforceInterface(INTERFACE_DESCRIPTOR)
        return when (code) {
            TRANSACT_UID -> {
                reply?.writeNoException()
                reply?.writeInt(Process.myUid())
                true
            }
            TRANSACT_QTAG -> {
                val action = data.readInt()
                reply?.writeNoException()
                val codeResult = runCatching { sendVendorQtag(action) }.getOrDefault(-1)
                reply?.writeInt(codeResult)
                true
            }
            TRANSACT_RESUME_POLLING -> {
                reply?.writeNoException()
                val status = runCatching { resumePolling() }.getOrDefault(-1)
                reply?.writeInt(status)
                true
            }
            else -> false
        }
    }

    private fun nfcService(): Pair<Class<*>, Any> {
        val existingType = cachedNfcType
        val existingSvc = cachedNfcService
        if (existingType != null && existingSvc != null) return existingType to existingSvc

        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "nfc") as? IBinder
            ?: throw IllegalStateException("NFC service unavailable")
        val type = Class.forName("android.nfc.INfcAdapter")
        val svc = Class.forName("android.nfc.INfcAdapter\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        cachedNfcType = type
        cachedNfcService = svc
        return type to svc
    }

    private fun sendVendorQtag(action: Int): Int {
        val (type, svc) = nfcService()
        val method: Method = type.getMethod(
            "sendVendorNciMessage",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ByteArray::class.java,
        )
        return try {
            val res = method.invoke(svc, 1, 15, 112, byteArrayOf(0x31, action.toByte()))
            (res as? Int) ?: -1
        } catch (e: InvocationTargetException) {
            throw (e.cause ?: e)
        }
    }

    private fun resumePolling(): Int {
        val (type, svc) = nfcService()
        val result = type.getMethod("resumePolling").invoke(svc)
        return (result as? Int) ?: 1
    }

    private fun resolveAppUid(): Int {
        val pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)
        val ipm = Class.forName("android.content.pm.IPackageManager")
        val pkg = BuildConfig.APPLICATION_ID
        val info: ApplicationInfo? = runCatching {
            ipm.getMethod("getApplicationInfo", String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pm, pkg, 0L, 0) as? ApplicationInfo
        }.getOrElse {
            ipm.getMethod("getApplicationInfo", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pm, pkg, 0, 0) as? ApplicationInfo
        }
        return info?.uid ?: throw IllegalStateException("Package $pkg not found")
    }

    companion object {
        const val INTERFACE_DESCRIPTOR = "${BuildConfig.APPLICATION_ID}.NfcSession"
        const val TRANSACT_UID = 4
        const val TRANSACT_QTAG = 5
        const val TRANSACT_RESUME_POLLING = 6
        private const val TRANSACT_SHIZUKU_DESTROY = 16777115
    }
}
