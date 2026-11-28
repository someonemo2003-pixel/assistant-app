package com.myassistant.app.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Sign-in to Google services with the phone's Google account (Google's own consent screen).
 * Nova never sees a password; Google keeps the grant and hands out a fresh access token on each call,
 * so nothing secret is stored here. Each service (Tasks, Gmail …) is a [Service] with its own scopes.
 */
object GoogleAuth {
    enum class Service(val key: String, val scopes: List<String>) {
        TASKS("gtasks", listOf("https://www.googleapis.com/auth/tasks")),
        GMAIL("gmail_api", listOf("https://www.googleapis.com/auth/gmail.modify")),
    }

    sealed class Auth {
        data class Token(val token: String) : Auth()
        data class NeedsUser(val intent: PendingIntent) : Auth()
        data class Failed(val why: String) : Auth()
    }

    class NotConnected(service: Service) : Exception("${service.name} مش مربوط")

    private fun prefs(ctx: Context, s: Service) = ctx.getSharedPreferences(s.key, Context.MODE_PRIVATE)
    fun connected(ctx: Context, s: Service) = prefs(ctx, s).getBoolean("connected", false)
    fun account(ctx: Context, s: Service) = prefs(ctx, s).getString("account", "").orEmpty()
    fun disconnect(ctx: Context, s: Service) = prefs(ctx, s).edit().clear().apply()

    private fun request(s: Service) = AuthorizationRequest.builder().setRequestedScopes(s.scopes.map { Scope(it) }).build()

    suspend fun authorize(ctx: Context, s: Service): Auth = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(ctx.applicationContext).authorize(request(s))
            .addOnSuccessListener { r -> cont.resume(fromResult(ctx, s, r)) }
            .addOnFailureListener { e -> cont.resume(Auth.Failed(e.message ?: e.javaClass.simpleName)) }
    }

    /** Result of Google's consent screen. */
    fun fromIntent(ctx: Context, s: Service, data: Intent?): Auth = runCatching {
        fromResult(ctx, s, Identity.getAuthorizationClient(ctx.applicationContext).getAuthorizationResultFromIntent(data))
    }.getOrElse { Auth.Failed(it.message ?: "اتلغى") }

    private fun fromResult(ctx: Context, s: Service, r: AuthorizationResult): Auth {
        if (r.hasResolution()) return r.pendingIntent?.let { Auth.NeedsUser(it) } ?: Auth.Failed("no consent screen")
        val token = r.accessToken ?: return Auth.Failed("no token")
        val e = prefs(ctx, s).edit().putBoolean("connected", true)
        r.toGoogleSignInAccount()?.email?.takeIf { it.isNotBlank() }?.let { e.putString("account", it) }
        e.apply()
        return Auth.Token(token)
    }

    /** A token for background calls; throws [NotConnected] when the user must connect first. */
    suspend fun token(ctx: Context, s: Service): String = when (val a = authorize(ctx, s)) {
        is Auth.Token -> a.token
        is Auth.NeedsUser -> { prefs(ctx, s).edit().putBoolean("connected", false).apply(); throw NotConnected(s) }
        is Auth.Failed -> throw java.io.IOException(a.why)
    }

    fun setAccount(ctx: Context, s: Service, email: String) = prefs(ctx, s).edit().putString("account", email).apply()
}
