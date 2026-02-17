package com.tokbox.sample.basicvideochat.network

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface APIService {

    @POST("verify/start")
    fun startVerify(@Body body: StartVerifyRequest): Call<StartVerifyResponse>

    @POST("verify/confirm")
    fun confirmVerify(@Body body: ConfirmVerifyRequest): Call<ConfirmVerifyResponse>

    @POST("video/token")
    fun getVideoToken(): Call<VideoTokenResponse>
}