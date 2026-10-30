package com.diego.frameme.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.diego.frameme.R
import com.diego.frameme.ui.components.ArStatusScreen
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.delay

/** Resolved, user-facing outcome of the ARCore capability check. */
private enum class ArStatus {
    Checking,
    Supported,
    InstallAvailable,
    UpdateAvailable,
    Unsupported,
    CheckFailed,
}

/**
 * Pre-flight ARCore gate.
 *
 * Runs before the AR experience is composed so unsupported devices never even
 * try to open a camera/GL session:
 *  - supported devices go straight to [content];
 *  - devices where ARCore is missing or outdated get an in-app install/update
 *    prompt (Play Store flow via [ArCoreApk.requestInstall]);
 *  - devices that are not ARCore-capable get a plain explanatory message.
 *
 * The check re-runs on every resume, so returning from the Play Store after an
 * install/update automatically unlocks the AR screen.
 */
@Composable
fun ArAvailabilityGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var status by remember { mutableStateOf(ArStatus.Checking) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        status = resolveArStatus(context)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (status != ArStatus.Checking) refreshKey++
    }

    when (status) {
        ArStatus.Supported -> content()

        ArStatus.Checking -> ArStatusScreen(
            icon = null,
            title = stringResource(R.string.ar_checking),
            message = "",
            busy = true,
        )

        ArStatus.InstallAvailable -> ArStatusScreen(
            icon = Icons.Filled.ViewInAr,
            title = stringResource(R.string.ar_install_title),
            message = stringResource(R.string.ar_install_message),
            primaryLabel = stringResource(R.string.ar_action_install),
            onPrimary = {
                requestArCoreInstall(activity) { refreshKey++ }
            },
            secondaryLabel = stringResource(R.string.ar_action_learn_more),
            onSecondary = { openArDevicesPage(context) },
        )

        ArStatus.UpdateAvailable -> ArStatusScreen(
            icon = Icons.Filled.SystemUpdate,
            title = stringResource(R.string.ar_update_title),
            message = stringResource(R.string.ar_update_message),
            primaryLabel = stringResource(R.string.ar_action_update),
            onPrimary = {
                requestArCoreInstall(activity) { refreshKey++ }
            },
            secondaryLabel = stringResource(R.string.ar_action_learn_more),
            onSecondary = { openArDevicesPage(context) },
        )

        ArStatus.Unsupported -> ArStatusScreen(
            icon = Icons.Filled.PhoneAndroid,
            title = stringResource(R.string.ar_unsupported_title),
            message = stringResource(R.string.ar_unsupported_message),
            primaryLabel = stringResource(R.string.ar_action_learn_more),
            onPrimary = { openArDevicesPage(context) },
            secondaryLabel = stringResource(R.string.ar_action_exit),
            onSecondary = { activity?.finish() },
        )

        ArStatus.CheckFailed -> ArStatusScreen(
            icon = Icons.Filled.Warning,
            title = stringResource(R.string.ar_failed_title),
            message = stringResource(R.string.ar_failed_message),
            primaryLabel = stringResource(R.string.ar_action_retry),
            onPrimary = { refreshKey++ },
        )
    }
}

/**
 * Queries ARCore for this device's capability, retrying the transient
 * "still checking" states a few times before giving up.
 */
private suspend fun resolveArStatus(context: Context): ArStatus {
    repeat(4) {
        val availability = runCatching {
            ArCoreApk.getInstance().checkAvailability(context)
        }.getOrNull() ?: return ArStatus.CheckFailed

        when {
            availability == ArCoreApk.Availability.SUPPORTED_INSTALLED -> return ArStatus.Supported
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> return ArStatus.InstallAvailable
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> return ArStatus.UpdateAvailable
            availability.isUnsupported -> return ArStatus.Unsupported
            availability.isTransient -> delay(300L)
            else -> return ArStatus.CheckFailed
        }
    }
    return ArStatus.CheckFailed
}

/**
 * Launches ARCore's install/update flow (Play Store, or an in-app prompt where
 * supported). [onDone] fires once the flow returns, so the gate can re-check.
 */
private fun requestArCoreInstall(activity: Activity?, onDone: () -> Unit) {
    val host = activity ?: return
    try {
        ArCoreApk.getInstance().requestInstall(host, true)
        onDone()
    } catch (e: UnavailableDeviceNotCompatibleException) {
        onDone()
    } catch (e: UnavailableUserDeclinedInstallationException) {
        onDone()
    }
}

private fun openArDevicesPage(context: Context) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://developers.google.com/ar/devices"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
