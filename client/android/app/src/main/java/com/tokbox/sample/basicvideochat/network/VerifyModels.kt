package com.tokbox.sample.basicvideochat.network

data class StartVerifyRequest(val phone_number: String)
data class StartVerifyResponse(val request_id: String, val check_url: String, val channel: String)

data class ConfirmVerifyRequest(val request_id: String, val code: String)
data class ConfirmVerifyResponse(val status: String)

data class VideoTokenResponse(val apiKey: String, val sessionId: String, val token: String)