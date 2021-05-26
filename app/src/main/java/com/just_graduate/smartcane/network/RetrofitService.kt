package com.just_graduate.smartcane.network

import com.just_graduate.smartcane.data.DetectedObject
import okhttp3.MultipartBody
import retrofit2.http.*

interface RetrofitService {
    /**
     * Image Segmentation 을 수행하는 딥 러닝 서버로
     * 실시간 촬영 이미지에 대한 해석 결과 값을 요청함
     */
    @Multipart
    @GET("/segmentation/")
    fun getImageSegmentationResult(
        @Part image: MultipartBody.Part,
    ): List<DetectedObject>

}