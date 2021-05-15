package com.just_graduate.smartcane.tflite

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
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
        val assetManager = context.assets
//        val model = loadModelFile(assetManager)
        val model: MappedByteBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
        val interpreter: Interpreter = Interpreter(model)

//        val options = Interpreter.Options()
//        options.setUseNNAPI(true)
//        val interpreter = Interpreter(model)

        val inputShape = interpreter.getInputTensor(0).shape()
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

        startTime = System.nanoTime()
//        val result = Array(1) { FloatArray(OUTPUT_CLASSES_COUNT) }
        val bufferSize = 3655680 * java.lang.Float.SIZE / java.lang.Byte.SIZE
        val modelOutput = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())

        val probBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 3655680), DataType.UINT8)
        interpreter?.run(byteBuffer.buffer, probBuffer.buffer)
        elapsedTime = (System.nanoTime() - startTime) / 1000000

        Log.d(TAG, "Preprocessing time = " + elapsedTime + "ms")

        var text = "OUTPUT TENSOR\n\n"

        modelOutput.rewind()
        val result = probBuffer.floatArray

        for (i in 0 until 1000) {
            Log.d(TAG, result[i].toString())
            text += result[i]
            text += ' '
        }

        return text
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
                .add(ResizeOp(inputImageHeight, inputImageWidth, ResizeOp.ResizeMethod.BILINEAR))
                .build()

        var tImage = TensorImage(DataType.FLOAT32)
        tImage.load(bitmap)
        tImage = imageProcessor.process(tImage)

        val pixels = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        bitmap.recycle()
        return tImage
    }

    private fun getOutputString(output: FloatArray): String {
        val maxIndex = output.indices.maxBy { output[it] } ?: -1
        return "Prediction Result: %d\nConfidence: %2f".format(maxIndex, output[maxIndex])
    }

    companion object {
        private const val TAG = "ImageSegmentation"

        private const val MODEL_FILE = "model.tflite"

        private const val FLOAT_TYPE_SIZE = 4
        private const val PIXEL_SIZE = 1

        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        private const val OUTPUT_CLASSES_COUNT = 10
    }
}