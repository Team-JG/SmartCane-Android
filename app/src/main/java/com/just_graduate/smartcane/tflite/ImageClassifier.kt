package com.just_graduate.smartcane.tflite

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import org.jetbrains.kotlinx.multik.api.d4array
import org.jetbrains.kotlinx.multik.api.mk
import org.jetbrains.kotlinx.multik.api.ndarray
import org.jetbrains.kotlinx.multik.ndarray.data.get
import org.jetbrains.kotlinx.multik.ndarray.operations.forEach
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.TensorFlowLite
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class ImageClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null

    var isInitialized = false
        private set

    private val executorService: ExecutorService = Executors.newCachedThreadPool()

    private var inputImageWidth: Int = 0
    private var inputImageHeight: Int = 0
    private var modelInputSize: Int = 0

    fun initialize(): Task<Void> {
        val task = TaskCompletionSource<Void>()
        executorService.execute {
            try {
                initializeInterpreter()
                task.setResult(null)
            } catch (e: IOException) {
                task.setException(e)
            }
        }

        return task.task
    }

    private fun initializeInterpreter() {
        val compatList = CompatibilityList()
        val options = Interpreter.Options().apply {
            this.setNumThreads(8)
        }

        val model: MappedByteBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
        val interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0).shape()

        inputShape.forEach {
            Log.d("FXXK", it.toString())  // 1, 272, 480, 3
        }

        inputImageWidth = inputShape[1]
        inputImageHeight = inputShape[2]
        modelInputSize = FLOAT_TYPE_SIZE * inputImageWidth * inputImageHeight * PIXEL_SIZE

        this.interpreter = interpreter
        isInitialized = true
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager): ByteBuffer {
        val fileDescriptor = assetManager.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength

        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun classify(bitmap: Bitmap): String {
        if (!isInitialized) {
            throw IllegalStateException("TF Lite Interpreter is not initialized yet")
        }

        var startTime: Long = System.nanoTime()
        val resizedImage =
                Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedImage)

        var elapsedTime: Long = (System.nanoTime() - startTime) / 1000000
        Log.d(TAG, "Preprocessing time = " + elapsedTime + "ms")

        val inputSize = inputImageWidth * inputImageHeight
        Log.d(TAG, inputSize.toString())

        startTime = System.nanoTime()

        val probBuffer = TensorBuffer.createFixedSize(intArrayOf(272, 480, 7, 4), DataType.UINT8)
        interpreter?.run(byteBuffer.buffer, probBuffer.buffer)
        elapsedTime = (System.nanoTime() - startTime) / 1000000

        Log.d(TAG, "Preprocessing time = " + elapsedTime + "ms")

        val result = probBuffer.floatArray
        Log.d("FXXK", probBuffer.typeSize.toString())

        getOutputInfo(result)
        return "OUTPUT TENSOR"
    }

    fun classifyAsync(bitmap: Bitmap): Task<String> {
        val task = TaskCompletionSource<String>()
        executorService.execute {
            val result = classify(bitmap)
            task.setResult(result)
        }
        return task.task
    }

    fun close() {
        executorService.execute {
            interpreter?.close()
            Log.d(TAG, "Closed TFLite interpreter")
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): TensorImage {
        val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputImageHeight, inputImageWidth, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                .add(NormalizeOp(127.5F, 127.5F))
                .build()

        var tImage = TensorImage(DataType.FLOAT32)

        tImage.load(bitmap)
        tImage = imageProcessor.process(tImage)
        bitmap.recycle()

//        val pixels = IntArray(inputImageWidth * inputImageHeight)
//        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
//        Log.d("FXXK", "inputImageHeight : $inputImageHeight")
//        Log.d("FXXK", "inputImageWidth : $inputImageWidth")
//        Log.d("FXXK", "inputTensorImage : ${inputImageWidth * inputImageHeight}")
//        Log.d("FXXK", "X 3 : ${inputImageWidth * inputImageHeight * 3}")
//        Log.d("FXXK", tImage.buffer.toString())

        return tImage
    }

    private fun getOutputInfo(output: FloatArray) {
        val size = 272 * 480
        val index = 0
        val sliced = arrayOf(output[index], output[index + size], output[index + size * 2],
                output[index + size * 3], output[index + size * 4],
                output[index + size * 5], output[index + size * 6])

//        Label [0] : ["background"]
//        Label [1] : ["bike_lane_normal", "sidewalk_asphalt", "sidewalk_urethane"]
//        Label [2] : ["caution_zone_stairs", "caution_zone_manhole", "caution_zone_tree_zone", "caution_zone_grating", "caution_zone_repair_zone"]
//        Label [3] : ["alley_crosswalk","roadway_crosswalk"]
//        Label [4] : ["braille_guide_blocks_normal", "braille_guide_blocks_damaged"]
//        Label [5] : ["roadway_normal","alley_normal","alley_speed_bump", "alley_damaged"]
//        Label [6] : ["sidewalk_blocks","sidewalk_cement" , "sidewalk_soil_stone", "sidewalk_damaged","sidewalk_other"]

        Log.d("getOutputInfo0", sliced[0].toString())
        Log.d("getOutputInfo1", sliced[1].toString())
        Log.d("getOutputInfo2", sliced[2].toString())
        Log.d("getOutputInfo3", sliced[3].toString())
        Log.d("getOutputInfo4", sliced[4].toString())
        Log.d("getOutputInfo5", sliced[5].toString())
        Log.d("getOutputInfo6", sliced[6].toString())

        Log.d("getOutputMax", sliced.maxByOrNull { it }.toString())

        val tensor = mk.ndarray(output, 272, 480, 7, 4)

//        val result = mk.math.maxD4(tensor, axis = 1)  // Invoke Error (loadLibrary 쪽에서 오류 나는 것 같음)

//        Log.d("Tensor", result[0].toString())

    }

    companion object {
        private const val TAG = "ImageSegmentation"
        private const val MODEL_FILE = "model.tflite"
        private const val FLOAT_TYPE_SIZE = 4
        private const val PIXEL_SIZE = 1
        private const val OUTPUT_CLASSES_COUNT = 7
    }
}