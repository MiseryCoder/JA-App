package eu.kanade.tachiyomi.data.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gets access to the user's Google Drive through Google Play services. Which Google Cloud project is used is
 * decided by the package name and signing certificate of the app, so there's no client ID in the code.
 */
@Inject
class GoogleDriveAuth(
    private val context: Context,
) {

    private val client
        get() = Identity.getAuthorizationClient(context)

    // Only the files this app creates in the user's Drive, not the rest of it
    private val request: AuthorizationRequest
        get() = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

    /**
     * Asks for access to Google Drive.
     *
     * @return the consent screen to launch if the user still has to grant access, or null if they already did.
     */
    suspend fun requestAccess(): PendingIntent? {
        val result = client.authorize(request).await()
        return if (result.hasResolution()) result.pendingIntent else null
    }

    /**
     * Reads the outcome of the consent screen that returned [data].
     *
     * @return null if the user granted access, or what went wrong.
     */
    fun getAccessError(data: Intent?): String? {
        return try {
            val result = client.getAuthorizationResultFromIntent(data)
            if (result.accessToken != null) null else "No access token"
        } catch (e: ApiException) {
            logcat(LogPriority.ERROR, e) { "Google Drive access was not granted" }
            e.message ?: e.statusCode.toString()
        }
    }

    /**
     * Returns a token to call the Drive API with. Google Play services caches and renews it.
     *
     * @throws SignInRequiredException if the user has to grant access again, which needs the settings screen.
     */
    suspend fun getAccessToken(): String {
        val result = client.authorize(request).await()
        return result.accessToken.takeUnless { result.hasResolution() }
            ?: throw SignInRequiredException(context.stringResource(MR.strings.sync_sign_in_required))
    }

    /**
     * Drops a cached [token] that Drive rejected, so the next one is new.
     */
    suspend fun clearToken(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    class SignInRequiredException(message: String) : Exception(message)
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
