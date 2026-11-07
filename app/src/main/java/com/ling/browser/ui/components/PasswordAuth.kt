package com.ling.browser.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 密码库解锁结果。[message] 为 null 表示验证成功。 */
data class PasswordUnlockResult(
    val success: Boolean,
    val message: String? = null,
)

/**
 * 用生物识别或设备凭据解锁密码库。
 *
 * `setDeviceCredentialAllowed(true)` 让没有录入指纹/人脸的设备（或识别失败后）
 * 退回系统 PIN/图案/密码 —— 两条路都不通时无法放行，不会退化成"直接打开"。
 *
 * 必须在主线程调用（Compose 的 `LaunchedEffect` / `rememberCoroutineScope` 都满足）。
 */
suspend fun authenticateForPasswordVault(
    activity: FragmentActivity,
    subtitle: String,
): PasswordUnlockResult = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(PasswordUnlockResult(success = true))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) {
                    cont.resume(
                        PasswordUnlockResult(
                            success = false,
                            message = passwordUnlockErrorText(errorCode),
                        ),
                    )
                }
            }

            // 单次指纹不匹配不需要结束流程：系统会让用户继续重试。
            override fun onAuthenticationFailed() = Unit
        },
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("解锁密码库")
        .setSubtitle(subtitle)
        .setDeviceCredentialAllowed(true)
        .build()

    prompt.authenticate(info)
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
}

/**
 * 把系统错误码翻成用户看得懂的一句话。
 *
 * 尤其要区分"用户自己取消"和"设备根本没有可用的验证方式"——后者如果
 * 只说"验证失败"，用户会一直重试，却不知道需要先去系统设置里加锁屏。
 */
fun passwordUnlockErrorText(errorCode: Int): String = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    -> "已取消验证"

    BiometricPrompt.ERROR_LOCKOUT -> "尝试次数过多，请稍后再试"
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
        "生物识别已被锁定，请先用锁屏密码解锁设备再重试"

    BiometricPrompt.ERROR_NO_BIOMETRICS ->
        "设备未录入指纹或人脸，请先在系统设置里添加"

    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    -> "设备没有可用的生物识别，请先设置锁屏密码后重试"

    else -> "验证失败，请重试"
}

/** 从任意 Context 找到宿主 Activity；Compose 里 `LocalContext` 常常不是它。 */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
