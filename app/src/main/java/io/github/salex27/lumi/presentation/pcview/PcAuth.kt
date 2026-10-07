package io.github.salex27.lumi.presentation.pcview

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * Unlock gate for "My PC": the platform BiometricPrompt (API 29+, no androidx.biometric because MainActivity is not a
 * FragmentActivity) with biometrics or the screen lock as fallback. It only gates the request for an unlock token.
 */
object PcAuth {
    /** True when the phone has a fingerprint / face / PIN / pattern the prompt can use. */
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(BiometricManager::class.java) ?: return false
            val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            return manager.canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS
        }
        return context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }

    fun prompt(context: Context, title: String, subtitle: String, onSuccess: () -> Unit, onFailure: () -> Unit): CancellationSignal {
        val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        }
        val signal = CancellationSignal()
        builder.build().authenticate(signal, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFailure()
        })
        return signal
    }
}
