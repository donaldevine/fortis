package com.fortis.wallet.ui

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fortis.wallet.LockSetup
import com.fortis.wallet.WalletViewModel
import com.fortis.wallet.data.LOCK_BIOMETRIC
import com.fortis.wallet.data.LOCK_PASSWORD
import com.fortis.wallet.data.SeedKeystore
import com.fortis.wallet.data.toHex
import com.fortis.wallet.ui.theme.Fx
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun rememberFragmentActivity(): FragmentActivity? {
    var ctx: Context? = LocalContext.current
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

class BiometricCancelled : Exception("cancelled")
class BiometricUnavailable(msg: String) : Exception(msg)

/** Show the system unlock prompt (fingerprint / face / device PIN) bound to
 *  [cipher]; resume with the unlocked cipher. */
suspend fun authenticate(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    cipher: Cipher,
): Cipher = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) cont.resume(c)
                else cont.resumeWithException(BiometricUnavailable("no cipher from prompt"))
            }

            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                ) {
                    cont.resumeWithException(BiometricCancelled())
                } else {
                    cont.resumeWithException(BiometricUnavailable(msg.toString()))
                }
            }

            override fun onAuthenticationFailed() { /* wrong finger — the prompt keeps going */ }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
}

/** True if this failure means the Keystore key is gone (device security removed). */
fun Throwable.isKeyInvalidated(): Boolean =
    this is KeyPermanentlyInvalidatedException ||
        cause is KeyPermanentlyInvalidatedException

// --- first-wallet: choosing how the app is locked ---

/** Drives the "fingerprint or password" choice when the first wallet is created. */
class LockChoice(val biometricAvailable: Boolean) {
    var useBiometric by mutableStateOf(biometricAvailable)
    var pw by mutableStateOf("")
    var pw2 by mutableStateOf("")
    val ready: Boolean get() = if (useBiometric) true else pw.length >= 8 && pw == pw2
}

@Composable
fun rememberLockChoice(): LockChoice {
    val ctx = LocalContext.current
    return remember { LockChoice(SeedKeystore.available(ctx)) }
}

@Composable
fun LockChoiceFields(choice: LockChoice) {
    if (choice.biometricAvailable) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(choice.useBiometric, { choice.useBiometric = it })
            Text("Lock the app with fingerprint or PIN", color = Fx.text)
        }
        if (choice.useBiometric) Text(
            "One unlock opens all your wallets. No password to remember — your " +
                "device's fingerprint/face or screen-lock PIN gets you in.",
            color = Fx.textFaint, fontSize = 12.sp,
        )
    }
    if (!choice.useBiometric) {
        Text("This password unlocks the app and every wallet in it.", color = Fx.textFaint, fontSize = 12.sp)
        Field(choice.pw, { choice.pw = it }, "App password", password = true)
        Field(choice.pw2, { choice.pw2 = it }, "Confirm password", password = true)
    }
}

/** Turn the choice into a [LockSetup]. The biometric path shows the system
 *  prompt; returns null if the user cancels it. */
suspend fun LockChoice.resolve(activity: FragmentActivity?): LockSetup? {
    if (!useBiometric) return LockSetup(LOCK_PASSWORD, pw, null)
    val act = activity ?: throw BiometricUnavailable("no activity for the unlock prompt")
    SeedKeystore.createKey()
    val cipher = try {
        authenticate(act, "Set up app lock", "Fortis Wallet", SeedKeystore.encryptCipher())
    } catch (e: BiometricCancelled) {
        SeedKeystore.deleteKey()
        return null
    } catch (e: Throwable) {
        SeedKeystore.deleteKey()
        throw e
    }
    val secret = SeedKeystore.newSecret()
    val wrapped = SeedKeystore.wrap(cipher, secret)
    return LockSetup(LOCK_BIOMETRIC, secret.toHex(), wrapped)
}

// --- the app-lock screen body ---

/** Biometric unlock: auto-prompts on open, with a retry button and a clear
 *  message if the Keystore key was invalidated (device security removed). */
@Composable
fun BiometricAppUnlock(vm: WalletViewModel) {
    val act = rememberFragmentActivity()
    val scope = rememberCoroutineScope()
    var invalidated by remember { mutableStateOf(false) }

    fun go() {
        val a = act ?: return
        val wrapped = vm.appWrappedSecret ?: return
        scope.launch {
            try {
                val cipher = authenticate(a, "Unlock Fortis Wallet", "", SeedKeystore.decryptCipher(wrapped))
                vm.appUnlockWithSecret(SeedKeystore.unwrap(cipher, wrapped).toHex())
            } catch (e: BiometricCancelled) {
                // stay put — the button retries
            } catch (e: Throwable) {
                if (e.isKeyInvalidated()) invalidated = true else vm.error = e.message
            }
        }
    }

    LaunchedEffect(Unit) { go() }

    if (invalidated) {
        Text(
            "Your device's screen lock changed, so the app can't be unlocked here " +
                "any more. Reinstall and restore each wallet from its recovery phrase.",
            color = Fx.bad, fontSize = 13.sp,
        )
    } else {
        PrimaryButton("Unlock") { go() }
    }
    ErrorText(vm.error)
}
