package com.just_graduate.smartcane.network

import android.util.Log
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory

import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

object NetworkHelper {
    private const val serverBaseUrl = "http://localhost"

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

                Timber.d("request: ${it.request()}")
                Timber.d("request header: ${it.request().headers}")

                // Response
                val response = it.proceed(request)

                Timber.d("response : $response")
                Timber.d("response header: ${response.headers}")
                response
            }.build()

    private val gson = GsonBuilder().setLenient().create()

    private val retrofit = Retrofit.Builder()
            .baseUrl(serverBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .build()

    val retrofitService: RetrofitService = retrofit.create(RetrofitService::class.java)
}
