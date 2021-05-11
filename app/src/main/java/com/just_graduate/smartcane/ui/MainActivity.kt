package com.just_graduate.smartcane.ui

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.databinding.DataBindingUtil
import com.just_graduate.smartcane.PERMISSIONS
import com.just_graduate.smartcane.R
import com.just_graduate.smartcane.REQUEST_ALL_PERMISSION
import com.just_graduate.smartcane.databinding.ActivityMainBinding
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.just_graduate.smartcane.tflite.ImageClassifier
import com.just_graduate.smartcane.util.*
import com.just_graduate.smartcane.util.Util.showToast
import com.just_graduate.smartcane.util.Util.textToSpeech
import com.just_graduate.smartcane.viewmodel.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.lang.Exception
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService


class MainActivity : AppCompatActivity() {
    private val viewModel by viewModel<MainViewModel>()
    var mBluetoothAdapter: BluetoothAdapter? = null
    var recv: String = ""

    lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraCaptureButton: Button
    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    private var imageClassifier = ImageClassifier(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.viewModel = viewModel

        if (!hasPermissions(this, PERMISSIONS)) {
            requestPermissions(PERMISSIONS, REQUEST_ALL_PERMISSION)
        }

        // View Component 들이 ViewModel Observing 시작
        initObserving()
        startCamera()

        /**
         * Image Classifier 초기화
         */
        imageClassifier.initialize().addOnFailureListener {
            Log.e(TAG, "Error to setting up classifier", it)
        }

        /**
         * 사진 촬영 후 Interpreter 실행
         */
        binding.cameraCaptureButton.setOnClickListener {
            takePhoto()
        }
    }

    // TODO : 실제 TF Lite 모델이 완성되면 해당 메소드를 특정 msec 간격으로 호출해야함 (최적화가 필요함, 현재 1회 추론에 약 7초로 매우 느림)
    private fun takePhoto() {
        // 참조 변수 (계속하여 변경되는 이미지 캡쳐본 대응)
        val imageCapture = imageCapture ?: return

        // 사진이 찍히고 난 뒤 실행되는 Listener 동작 정의
        // -CallBack 메소드는 OnImageCapturedCallback() 을 사용하여
        //  Bitmap 형식의 이미지를 핸들링할 수 있도록 함
        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),

                object : ImageCapture.OnImageCapturedCallback() {

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Capture Failed: ${exception.message}", exception)
                    }

                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = imageProxyToBitmap(image)
                        showToast("Capture Succeeded: $image")

                        // TF Lite 모델에 이미지 입력
                        imageClassifier.classifyAsync(bitmap)
                                .addOnSuccessListener { resultText ->
                                    Log.d(TAG, "SUCCESS!")
                                    Log.d(TAG, resultText)
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "ERROR")
                                }
                        super.onCaptureSuccess(image)
                    }
                }
        )
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

    private fun classifyImage(bitmap: Bitmap?) {
        if ((bitmap != null) && (imageClassifier.isInitialized)) {
            imageClassifier
                    .classifyAsync(bitmap)
                    .addOnSuccessListener {
                        // TODO
                        Log.d(TAG, it)
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "Error Classifying Drawing", it)
                    }
        }
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
                        it.setSurfaceProvider(binding.viewFinder.createSurfaceProvider())
                    }

            imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private val startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val intent = result.data
                    viewModel.onClickConnect()
                }
            }

    private fun initObserving() {
        // Progress
        viewModel.inProgress.observe(this, {
            if (it.getContentIfNotHandled() == true) {
                viewModel.inProgressView.set(true)
            } else {
                viewModel.inProgressView.set(false)
            }
        })

        // Progress text
        viewModel.progressState.observe(this, {
            viewModel.txtProgress.set(it)
        })

        // Bluetooth On 요청
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

        // Bluetooth Connect Error
        viewModel.connectError.observe(this, {
            Util.showToast("연결 도중 오류가 발생하였습니다. 기기를 확인해주세요.")
            viewModel.setInProgress(false)
        })

        //Data Receive
        viewModel.putTxt.observe(this, {
            if (it != null) {
                recv += it
                viewModel.txtRead.set(recv)
            }
        })
    }

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

    // Permission check
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

    override fun onResume() {
        super.onResume()
        viewModel.txtRead.set("Here you can see the message come")
    }

    override fun onPause() {
        super.onPause()
        viewModel.unregisterReceiver()
    }

    override fun onBackPressed() {
        // super.onBackPressed()
        viewModel.setInProgress(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "SmartCane-MainActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}