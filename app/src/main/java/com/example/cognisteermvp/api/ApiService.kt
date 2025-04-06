package com.example.cognisteermvp.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @GET("/health")
    suspend fun checkHealth(): HealthResponse

    @POST("/query")
    suspend fun processQuery(@Body request: QueryRequest): QueryResponse
}

data class HealthResponse(
    val status: String,
    val message: String
)

data class QueryRequest(
    @SerializedName("query") // Ensure this matches the key expected by the backend.
    val query: String,
    @SerializedName("sessionId")
    val sessionId: String
)

// Define a data class for each protocol object.
data class Protocol(
    @SerializedName("protocol_id")
    val protocolId: String,
    val title: String,
    val content: String
)

// Update QueryResponse to expect a "results" field that contains a list of Protocol objects.
data class QueryResponse(
    val status: String,
    @SerializedName("results")
    val results: List<Protocol>
)

