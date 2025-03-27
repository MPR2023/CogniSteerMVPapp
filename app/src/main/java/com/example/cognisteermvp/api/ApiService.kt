package com.example.cognisteermvp.api

import retrofit2.http.GET

interface ApiService {
    @GET("/health")
    suspend fun checkHealth(): HealthResponse
}

data class HealthResponse(
    val status: String,
    val message: String
)