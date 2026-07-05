package io.github.jiro.expensetracker.sync.google

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@Singleton
internal class GoogleSignInAuthImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GoogleAuth {

    private val client by lazy {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (webClientId.isNotEmpty()) {
            builder.requestIdToken(webClientId).requestServerAuthCode(webClientId)
        }
        GoogleSignIn.getClient(context, builder.build())
    }

    override suspend fun getLastSignedInAccount(): GoogleAccountSnapshot? =
        withContext(Dispatchers.IO) {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            account.toSnapshot()
        }

    override fun buildSignInIntent(): Intent = client.signInIntent

    override suspend fun extractAccountFromResult(data: Intent?): GoogleAccountSnapshot? =
        withContext(Dispatchers.IO) {
            if (data == null) return@withContext null
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                task.await().toSnapshot()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.google.android.gms.common.api.ApiException) {
                null
            }
        }

    private fun GoogleSignInAccount.toSnapshot(): GoogleAccountSnapshot =
        GoogleAccountSnapshot(
            email = email.orEmpty(),
            serverAuthCode = serverAuthCode,
            idToken = idToken,
        )

    private companion object {
        val webClientId: String get() = BuildConfig.DEFAULT_WEB_CLIENT_ID
    }
}