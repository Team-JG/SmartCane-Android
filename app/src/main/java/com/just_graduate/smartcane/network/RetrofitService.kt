package com.just_graduate.smartcane.network

import com.just_graduate.smartcane.data.SegmentationResponse
import okhttp3.MultipartBody
import retrofit2.http.*

interface RetrofitService {
    /**
     * Image Segmentation 을 수행하는 딥 러닝 서버로
     * 실시간 촬영 이미지에 대한 해석 결과 값을 요청함
     */
    @Multipart
    @POST("/api/direct/")
    suspend fun getImageSegmentationResult(
            @Part file: MultipartBody.Part,
    ): SegmentationResponse

}