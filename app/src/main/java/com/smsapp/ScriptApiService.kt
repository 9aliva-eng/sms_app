package com.smsapp

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for Google Apps Script web app.
 * Used for write operations (API key doesn't support writes).
 */
interface ScriptApiService {

    @POST(".")
    suspend fun writeRange(
        @Body body: ScriptWriteRequest
    ): Response<ScriptResponse>
}

data class ScriptWriteRequest(
    val sheet: String,
    val range: String,
    val values: List<List<String>>
)

data class ScriptResponse(
    val status: String,
    val message: String? = null
)
