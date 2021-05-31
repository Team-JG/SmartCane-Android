package com.just_graduate.smartcane.ui

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.databinding.DataBindingUtil
import com.just_graduate.smartcane.R
import com.just_graduate.smartcane.databinding.ActivityMainBinding
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaParser
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.just_graduate.smartcane.Constants.CAUTION_ZONE
import com.just_graduate.smartcane.Constants.CROSS_WALK
import com.just_graduate.smartcane.Constants.FRONT
import com.just_graduate.smartcane.Constants.PERMISSIONS
import com.just_graduate.smartcane.Constants.REQUEST_ALL_PERMISSION
import com.just_graduate.smartcane.Constants.ROAD_WAY
import com.just_graduate.smartcane.data.DetectedObject
import com.just_graduate.smartcane.data.SegmentationResponse
import com.just_graduate.smartcane.tflite.ImageClassifier
import com.just_graduate.smartcane.util.*
import com.just_graduate.smartcane.util.Util.showToast
import com.just_graduate.smartcane.util.Util.textToSpeech
import com.just_graduate.smartcane.viewmodel.MainViewModel
import com.theartofdev.edmodo.cropper.CropImage
import com.theartofdev.edmodo.cropper.CropImageView
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.koin.android.ext.android.bind
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.io.*
import java.lang.Exception
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import kotlin.random.Random


class MainActivity : AppCompatActivity() {
    private val viewModel by viewModel<MainViewModel>()
    var recv: String = ""

    lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService
    private var imageClassifier = ImageClassifier(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // View Component 들이 ViewModel Observing 시작
        initObserving()
        // Camera X 인스턴스 초기화
        startCamera()

        /**
         * 전체 화면으로 앱 실행
         */
        this.window?.apply {
            this.statusBarColor = Color.TRANSPARENT
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }


        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this

        if (!hasPermissions(this, PERMISSIONS)) {
            requestPermissions(PERMISSIONS, REQUEST_ALL_PERMISSION)
        }

        /**
         * Image Classifier 초기화
         * TODO : 딥 러닝 API 호출 시 Image Classifier 제거
         */
        imageClassifier.initialize().addOnFailureListener {
            Timber.e(it)
        }

        /**
         * 사진 촬영 후 Semantic Segmentation API 호출
         */
        binding.cameraCaptureButton.setOnClickListener {
            takePhoto()
        }

        /**
         * 임의 이미지 입력 후 Semantic Segmentation API 호출
         */
        binding.loadImageButton.setOnClickListener {
            loadImage()
        }
    }

    // TODO : 실제 TF Lite 모델이 완성되면 해당 메소드를 특정 msec 간격으로 호출해야함 (최적화가 필요함, 현재 1회 추론에 약 7초로 매우 느림)
    private fun takePhoto() {
        // 참조 변수 (계속하여 변경되는 이미지 캡쳐본 대응)
        val imageCapture = imageCapture ?: return

        // 사진이 찍히고 난 뒤 실행되는 Listener 동작 정의
        // - CallBack 메소드는 OnImageCapturedCallback() 을 사용하여
        // - Bitmap 형식의 이미지를 핸들링할 수 있도록 함
        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Capture Failed: ${exception.message}", exception)
                    }

                    /**
                     * 촬영한 이미지를 통해 딥 러닝 서버 API 를 호출하여, 결과를 핸들링함
                     * - ImageProxy => Bitmap => File => MultipartBody.Part => API 호출 동작 수행
                     * - API 호출이 완료되면, segmentationResult 라는 LiveData 에 API 호출 결과 담김
                     */
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // MultipartBody 로 만들어주기 위해 File 객체로 변환
                        val bitmap = imageProxyToBitmap(image)

                        showToast("Capture Succeeded: $image")

//                     TF Lite 모델에 이미지 입력
//                        imageClassifier.classifyAsync(bitmap)
//                                .addOnSuccessListener { result ->
//                                    Log.d(TAG, "SUCCESS!")
//                                    Log.d(TAG, result.itemsFound.toString())
//                                    binding.segmentationResultTextView.text = result.itemsFound.keys.toString()
//                                    bitmap.recycle()
//                                }
//                                .addOnFailureListener { e ->
//                                    Log.e(TAG, "ERROR")
//                                }

                        val body: MultipartBody.Part = buildImageBodyPart(bitmap)
                        // Semantic Segmentation API 호출
                        viewModel.getSegmentationResult(body)

                        image.close()
                        super.onCaptureSuccess(image)

                    }
                }
        )
    }

    /**
     * Retrofit 을 통해 이미지 POST API 구현을 하기 위해서는,
     * MultipartBody.Part 형태로 데이터를 넣어야 한다.
     * - Bitmap => File => MultipartBody.Part 변환 동작 수행
     */
    private fun buildImageBodyPart(bitmap: Bitmap): MultipartBody.Part {
        val imageFile = convertBitmapToFile(bitmap)
        val reqFile = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("file", imageFile.name, reqFile)
    }

    /**
     * MultipartBody.Part 를 만들기 위해서는 File 객체가 있어야함
     * - Bitmap => File 변환 동작 수행
     */
    private fun convertBitmapToFile(bitmap: Bitmap): File {
        val randomNumber = Random.nextInt(0, 1000000000).toString()
        //create a file to write bitmap data
        val file = File(this.cacheDir, "road_${randomNumber}.jpeg")
        file.createNewFile()

        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 0 /*ignored for PNG*/, bos)
        val bitMapData = bos.toByteArray()

        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        try {
            fos?.write(bitMapData)
            fos?.flush()
            fos?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
//        bitmap.recycle()
        return file
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 업로드를 위한 사진이 선택 및 편집되면 Uri 형태로 결과가 반환됨
        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            val result = CropImage.getActivityResult(data)

            if (resultCode == Activity.RESULT_OK) {
                val resultUri = result.uri
                val bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, resultUri)

//                imageClassifier.classifyAsync(bitmap)
//                        .addOnSuccessListener {
//                            Log.d(TAG, "SUCCESS!")
//                            Log.d(TAG, it.itemsFound.toString())
//                            binding.segmentationResultTextView.text = it.itemsFound.keys.toString()
//                            bitmap.recycle()
//                        }
//                        .addOnFailureListener { e ->
//                            Log.e(TAG, "ERROR")
//                        }

                val body: MultipartBody.Part = buildImageBodyPart(bitmap)
                viewModel.getSegmentationResult(body)

            } else if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
                Log.e("Error Image Selecting", "이미지 선택 및 편집 오류")
            }
        }
    }

    private fun loadImage() {
        CropImage.activity()
                .setGuidelines(CropImageView.Guidelines.ON)
                .setActivityTitle("이미지 추가")
                .setCropShape(CropImageView.CropShape.RECTANGLE)
                .setCropMenuCropButtonTitle("완료")
                .setRequestedSize(272, 480)
                .start(this)
    }

    /**
     * CameraX API 인 takePicture() 결과로 생성된 ImageProxy 를 인자로 받아
     * Bitmap 데이터를 생성해주는 메소드
     * - 생성된 Bitmap 으로 TF Lite 모델에 입력을 하면 됨
     */

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        // 인자로 Runnable 객체, getMainExecutor (Main thread 에서 동작하는 Executor 리턴함) 넘김
        cameraProviderFuture.addListener(Runnable {
            // 앱 Lifecycle 에 카메라 Lifecycle 바인드
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // 카메라 프리뷰 (XML 에서 만들었던 PreviewView 사용)
            val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    }

            imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_180)
                    .build()

            // 후면 카메라 기본값으로 사용
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 사용 중이던 CameraProvider 모두 unbind() 후 rebinding 함
                cameraProvider.unbindAll()

                // CameraProvider bind() -> CameraSelector, Preview 객체 넘김
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private val startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val intent = result.data
                    viewModel.onClickConnect()
                }
            }

    private fun initObserving() {
        // Loading Progress
        viewModel.inProgress.observe(this, {
            if (it.getContentIfNotHandled() == true) {
                viewModel.inProgressView.set(true)
            } else {
                viewModel.inProgressView.set(false)
            }
        })

        // Loading Progress text
        viewModel.progressState.observe(this, {
            viewModel.txtProgress.set(it)
        })

        // Bluetooth ON 요청
        viewModel.requestBleOn.observe(this, {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startForResult.launch(enableBtIntent)
        })

        // Bluetooth 연결/해제 이벤트
        viewModel.connected.observe(this, {
            if (it != null) {
                if (it) {
                    viewModel.setInProgress(false)
                    viewModel.btnConnected.set(true)
                    showToast(getString(R.string.success_connect_cane))
                    textToSpeech(getString(R.string.success_connect_cane))
                } else {
                    viewModel.setInProgress(false)
                    viewModel.btnConnected.set(false)
                    showToast(getString(R.string.disconnect_connect_cane))
                    textToSpeech(getString(R.string.disconnect_connect_cane))
                }
            }
        })

        // Bluetooth 연결 오류 이벤트
        viewModel.connectError.observe(this, {
            Util.showToast("연결 도중 오류가 발생하였습니다. 기기를 확인해주세요.")
            viewModel.setInProgress(false)
        })

        /**
         * SegmentationResult 를 담는 LiveData 옵저빙
         * - 값 바뀔 때마다 (API 호출 시마다) 결과 해석 메소드 실행 (TTS 재생)
         */
        viewModel.segmentationResult.observe(
                this@MainActivity,
                {
                    interpretImageSegmentationResult(it)
                }
        )

        /**
         * 아두이노 (지팡이) 낙상 감지 이벤트 관찰
         * - true -> 낙상 감지 데이터 수신, 20초 카운트 다운 시작 (완료 시 SOS 호출)
         * - false -> 사용자가 다시 지팡이를 잡았다는 데이터 수신, 카운트 다운 중지
         */
        viewModel.isFallDetected.observe(this, {
            if (it == true) viewModel.startCountDown()
            else viewModel.cancelCountDown()
        })

        /**
         * 사용자의 위치를 10초 주기로 업데이트하기 위한 LocationManager 초기화
         */
        viewModel.initLocationManager(context = this)
    }


    /**
     * 서버에서 응답받은 Image Semantic Segmentation 결과를 해석하는 메소드
     * - 왼쪽, 정면, 오른쪽 각각에 어떤 레이블이 존재하는지에 대해 정리
     * - 이후, 정리된 결과값을 통해 TTS 호출 동작 구현
     */
    private fun interpretImageSegmentationResult(data: SegmentationResponse) {
        val result: List<DetectedObject> = data.result
        if (result.isEmpty()) {
            Timber.d("감지된 객체 없음")
            return
        }

        result.forEach {
            Timber.d("direction : ${it.direction}, object : ${it.label}")
        }

        var message = "전방에 "
        val labelNameMap = mapOf(CAUTION_ZONE to "가로수, 맨홀 뚜껑 등의 주의 구역", CROSS_WALK to "횡단보도", ROAD_WAY to "골목길 혹은 차도")
        // 정면 (front) 에 주의 구역, 횡단보도, 차도 등이 감지되면 TTS 메세지에 추가
        result.forEachIndexed { index, it ->
            if (index == result.size - 1) {
                message += if (it === result[result.size - 1]) {
                    // 적절한 주격조사를 붙이기 위해 '주의 구역'만
                    if (it.label == CAUTION_ZONE) {
                        "${labelNameMap[it.label]}이 "
                    } else {
                        "${labelNameMap[it.label]}가 "
                    }
                } else {
                    "${labelNameMap[it.label]}, 그리고"
                }
            }
        }

        message += "있습니다. 주의하세요."
        Timber.d(message)
        textToSpeech(message)  // Text-To-Speech 로 만들어진 메세지 재생
        binding.segmentationResultTextView.text = message
    }


    /**
     * Permissions Array 에 있는 권한들을 갖고 있는지 검사
     */
    private fun hasPermissions(context: Context?, permissions: Array<String>): Boolean {
        for (permission in permissions) {
            if (context?.let { ActivityCompat.checkSelfPermission(it, permission) }
                    != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Permission 검사
     */
    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String?>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_ALL_PERMISSION -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
                } else {
                    requestPermissions(permissions, REQUEST_ALL_PERMISSION)
                    Toast.makeText(this, "Permissions must be granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.unregisterReceiver()

//        cameraExecutor.shutdown()
    }

    override fun onBackPressed() {
        // super.onBackPressed()
        viewModel.setInProgress(false)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SmartCane-MainActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10

    }
}