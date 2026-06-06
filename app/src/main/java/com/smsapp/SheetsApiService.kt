package com.smsapp

import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for Google Sheets API v4.
 * Base URL: https://sheets.googleapis.com/v4/spreadsheets/
 */
interface SheetsApiService {

    /**
     * Read a range of cells.
     * GET /v4/spreadsheets/{spreadsheetId}/values/{range}?key={apiKey}
     */
    @GET("{spreadsheetId}/values/{range}")
    suspend fun getValues(
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Query("key") apiKey: String
    ): Response<SheetsResponse>

    /**
     * Write a single range.
     * PUT /v4/spreadsheets/{spreadsheetId}/values/{range}?valueInputOption=RAW&key={apiKey}
     */
    @PUT("{spreadsheetId}/values/{range}")
    suspend fun updateValues(
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Query("valueInputOption") valueInputOption: String = "RAW",
        @Query("key") apiKey: String,
        @Body body: ValueRange
    ): Response<Any>

    /**
     * Batch update multiple ranges in one request.
     * POST /v4/spreadsheets/{spreadsheetId}/values:batchUpdate?key={apiKey}
     */
    @POST("{spreadsheetId}/values:batchUpdate")
    suspend fun batchUpdate(
        @Path("spreadsheetId") spreadsheetId: String,
        @Query("key") apiKey: String,
        @Body body: BatchUpdateRequest
    ): Response<Any>
}
