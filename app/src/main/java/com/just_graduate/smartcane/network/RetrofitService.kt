package com.just_graduate.smartcane.network

import com.just_graduate.smartcane.data.SegmentationResult
import io.reactivex.rxjava3.core.Single
import okhttp3.MultipartBody
import retrofit2.http.*

interface RetrofitService {
    @Multipart
    @GET("/segmentation/")
    fun getSegmentationResult(
        @Part image: MultipartBody.Part,
    ): Single<SegmentationResult>

}