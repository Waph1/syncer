package io.github.waph1.syncer.source

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Thrown when the user must grant access interactively (Google's consent screen). */
class AuthorizationRequiredException(val pendingIntent: PendingIntent?, message: String) : Exception(message)

/** Google authorization is misconfigured (e.g. no OAuth client for this package/SHA-1). */
class AuthConfigurationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * OAuth access tokens for Google REST APIs (Tasks, Keep) via Google Identity Services'
 * AuthorizationClient, for an account already present on the device. No password or client
 * secret is stored: Google identifies the app by package name + signing certificate SHA-1,
 * which must be registered as an "Android" OAuth client in a Google Cloud project (see README).
 * Once the user has consented, tokens are returned without UI, also from background work.
 */
class GoogleAuth(context: Context) {
    private val client = Identity.getAuthorizationClient(context)

    /** Blocking: call from a background thread. */
    fun token(accountName: String, scope: String): String {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(scope)))
            .setAccount(Account(accountName, CalendarSource.GOOGLE_ACCOUNT_TYPE))
            .build()
        val result = try {
            Tasks.await(client.authorize(request), 60, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw mapFailure(e.cause ?: e)
        } catch (e: TimeoutException) {
            throw IOException("Timeout durante l'autorizzazione Google", e)
        }
        if (result.hasResolution()) {
            throw AuthorizationRequiredException(result.pendingIntent, "Autorizzazione Google necessaria: apri l'app e concedi l'accesso")
        }
        return result.accessToken ?: throw IOException("Google non ha restituito un token di accesso")
    }

    /** Drops a rejected token from Play services' cache so that the next request gets a fresh one. */
    fun invalidate(token: String) {
        runCatching {
            Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(token).build()), 30, TimeUnit.SECONDS)
        }
    }

    private fun mapFailure(e: Throwable): Exception {
        if (e !is ApiException) return IOException("Errore di autorizzazione Google: ${e.message}", e)
        return when (e.statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR -> AuthConfigurationException(
                "App non registrata su Google Cloud: crea un client OAuth \"Android\" con package e SHA-1 " +
                    "mostrati in Impostazioni › Info, e abilita l'API nel progetto",
                e,
            )
            CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.INVALID_ACCOUNT -> AuthConfigurationException(
                "Account Google non disponibile sul dispositivo: selezionalo di nuovo nelle impostazioni",
                e,
            )
            CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT ->
                IOException("Rete non disponibile per l'autorizzazione Google", e)
            else -> AuthConfigurationException(
                "Autorizzazione Google non riuscita: ${CommonStatusCodes.getStatusCodeString(e.statusCode)}",
                e,
            )
        }
    }

    companion object {
        const val SCOPE_TASKS = "https://www.googleapis.com/auth/tasks.readonly"
        const val SCOPE_KEEP = "https://www.googleapis.com/auth/keep.readonly"
    }
}
