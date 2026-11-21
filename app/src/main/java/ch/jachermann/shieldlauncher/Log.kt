package ch.jachermann.shieldlauncher

import android.util.Log

private const val TAG = "ShieldLauncher"

/** Fehler protokollieren statt still zu verschlucken (sichtbar mit: adb logcat -s ShieldLauncher). */
fun logW(e: Throwable, msg: String? = null) {
    Log.w(TAG, msg ?: e.message ?: e.javaClass.simpleName, e)
}
