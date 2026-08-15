package org.stypox.dicio.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/**
 * Wraps the app content behind a device-authentication (fingerprint / face / PIN) gate.
 *
 * When [enabled] is true, [content] is only shown after the user authenticates with their device
 * credentials via [BiometricPrompt]. This gives the "only I can open it" property of a personal
 * assistant without inventing a custom password: it reuses the phone's own secure lock.
 *
 * If the device has no biometrics/credential set up, or the host is not a [FragmentActivity], the
 * gate fails open (shows the content) so the app never becomes unusable — the OS lock screen still
 * protects the device itself.
 */
@Composable
fun AppLockGate(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var unlocked by remember { mutableStateOf(false) }
    var canAuthenticate by remember {
        mutableStateOf(
            BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS) ==
                BiometricManager.BIOMETRIC_SUCCESS
        )
    }

    // if we can't authenticate (no host activity or no credential set), fail open
    if (unlocked || activity == null || !canAuthenticate) {
        content()
        return
    }

    LaunchedEffect(Unit) {
        promptUnlock(
            activity = activity,
            onSuccess = { unlocked = true },
            onUnavailable = { canAuthenticate = false },
        )
    }

    // shown while the biometric prompt is up, and if the user dismisses it
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null)
        Text(
            "Locked",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Authenticate to use your assistant.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = {
            promptUnlock(
                activity = activity,
                onSuccess = { unlocked = true },
                onUnavailable = { canAuthenticate = false },
            )
        }) {
            Text("Unlock")
        }
    }
}

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

private fun promptUnlock(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onUnavailable: () -> Unit,
) {
    try {
        val prompt = BiometricPrompt(
            activity,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // hardware/config errors mean we can't gate; fail open to stay usable
                    if (errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                        errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                        errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE
                    ) {
                        onUnavailable()
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock your assistant")
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    } catch (t: Throwable) {
        onUnavailable()
    }
}
