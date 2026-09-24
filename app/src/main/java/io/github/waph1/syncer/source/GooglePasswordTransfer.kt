package io.github.waph1.syncer.source

import android.app.Activity
import androidx.credentials.providerevents.ProviderEventsManager
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import androidx.credentials.providerevents.exception.ImportCredentialsException
import androidx.credentials.providerevents.exception.ImportCredentialsNoExportOptionException
import androidx.credentials.providerevents.transfer.CredentialTypes
import androidx.credentials.providerevents.transfer.ImportCredentialsRequest
import io.github.waph1.syncer.format.PasswordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Receives passwords from Google Password Manager through Android's credential transfer
 * (FIDO Credential Exchange): the system shows the list of apps that can export, the user picks
 * Google Password Manager and confirms. No plaintext file is created on the way.
 */
object GooglePasswordTransfer {

    /** Returns null when the user cancelled. */
    suspend fun import(activity: Activity): List<PasswordEntry>? {
        val request = ImportCredentialsRequest(
            setOf(
                CredentialTypes.CREDENTIAL_TYPE_BASIC_AUTH,
                CredentialTypes.CREDENTIAL_TYPE_NOTE,
                CredentialTypes.CREDENTIAL_TYPE_TOTP,
            ),
            emptySet(),
        )
        val response = try {
            ProviderEventsManager.create(activity).importCredentials(activity, request)
        } catch (e: ImportCredentialsCancellationException) {
            return null
        }
        return withContext(Dispatchers.Default) { CxfParser.parse(response.response.responseJson) }
    }

    fun describe(e: ImportCredentialsException): String = when (e) {
        is ImportCredentialsNoExportOptionException ->
            "Nessuna app disponibile per esportare le password: aggiorna Google Play services o usa \"Importa file CSV\""
        else -> "Trasferimento non riuscito: ${e.errorMessage ?: e.type}"
    }
}
