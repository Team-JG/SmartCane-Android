package com.just_graduate.smartcane.network

import android.os.Handler.createAsync
import android.util.Log
import androidx.core.os.HandlerCompat.createAsync
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory

import retrofit2.converter.gson.GsonConverterFactory

object NetworkHelper {
    private const val serverBaseUrl = "127.0.0.1"

    var token: String = ""

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        })

        .addInterceptor {
            // Request
            val request = it.request()
                .newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()

            Log.d("OkHTTP", "request: ${it.request()}")
            Log.d("OkHTTP", "request header: ${it.request().headers}")

            // Response
            val response = it.proceed(request)

            Log.d("OkHTTP", "response : $response")
            Log.d("OkHTTP", "response header: ${response.headers}")
            response
        }.build()

    private val gson = GsonBuilder().setLenient().create()

    private val retrofit = Retrofit.Builder()
        .baseUrl(serverBaseUrl)
        .client(okHttpClient)
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    val retrofitService: RetrofitService = retrofit.create(RetrofitService::class.java)
}
