package com.opirl.app.chat

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.opirl.app.engine.chat.youtube.YOUTUBE_SCOPE
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class YouTubeAccess(val token: String?, val consent: PendingIntent?)

class YouTubeAuth(private val context: Context) {
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(YOUTUBE_SCOPE))).build()

    suspend fun authorize(): YouTubeAccess? = suspendCancellableCoroutine { continuation ->
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { continuation.resume(YouTubeAccess(it.accessToken, it.pendingIntent)) }
            .addOnFailureListener { continuation.resume(null) }
    }

    fun token(result: Intent?): String? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result).accessToken }.getOrNull()
}
