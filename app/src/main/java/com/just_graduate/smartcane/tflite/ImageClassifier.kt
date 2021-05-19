package com.just_graduate.smartcane.tflite

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.camera.core.internal.utils.ImageUtil
import androidx.core.graphics.ColorUtils
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.just_graduate.smartcane.util.ImageUtils
import org.jetbrains.kotlinx.multik.api.mk
import org.jetbrains.kotlinx.multik.api.ndarray
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
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
import kotlin.random.Random

class ImageClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null

    var isInitialized = false
        private set

    private val executorService: ExecutorService = Executors.newCachedThreadPool()

    private val segmentationMasks: ByteBuffer

    private var inputImageWidth: Int = 272
    private var inputImageHeight: Int = 480
    private var modelInputSize: Int = 0

    init {
        segmentationMasks =
            ByteBuffer.allocateDirect(1 * inputImageWidth * inputImageHeight * NUM_CLASSES * 4)
        segmentationMasks.order(ByteOrder.nativeOrder())
    }

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

    private fun classify(bitmap: Bitmap): ModelExecutionResult {
        if (!isInitialized) {
            throw IllegalStateException("TF Lite Interpreter is not initialized yet")
        }

        var startTime: Long = System.nanoTime()
        val scaledBitmap =
            ImageUtils.scaleBitmapAndKeepRatio(
                bitmap,
                inputImageWidth, inputImageHeight
            )
        if (scaledBitmap != bitmap){
            bitmap.recycle()
        }
//
//        val byteBuffer =
//            ImageUtils.bitmapToByteBuffer(
//                scaledBitmap,
//                inputImageWidth,
//                inputImageHeight,
//                IMAGE_MEAN,
//                IMAGE_STD
//            )

        val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)

        var elapsedTime: Long = (System.nanoTime() - startTime) / 1000000
        Log.d(TAG, "Preprocessing time = " + elapsedTime + "ms")

        val inputSize = inputImageWidth * inputImageHeight
        Log.d(TAG, inputSize.toString())

        startTime = System.nanoTime()

        val probBuffer =
            ByteBuffer.allocateDirect(1 * inputImageWidth * inputImageHeight * NUM_CLASSES * 4)
        interpreter?.run(byteBuffer.buffer, probBuffer)

        val (maskImageApplied, maskOnly, itemsFound) =
            convertByteBufferMaskToBitmap(
                segmentationMasks, inputImageWidth, inputImageHeight, scaledBitmap,
                segmentColors
            )


        elapsedTime = (System.nanoTime() - startTime) / 1000000

        Log.d(TAG, "Preprocessing time = " + elapsedTime + "ms")

        val result = probBuffer
        Log.d("FXXK", probBuffer.toString())

        return ModelExecutionResult(
            maskImageApplied,
            scaledBitmap,
            maskOnly,
            itemsFound
        )
    }

    fun classifyAsync(bitmap: Bitmap): Task<ModelExecutionResult> {
        val task = TaskCompletionSource<ModelExecutionResult>()
        executorService.execute {
            val result = classify(bitmap)
            task.setResult(result)
        }
        return task.task
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): TensorImage {
        val imageProcessor = ImageProcessor.Builder()
            .add(
                ResizeOp(
                    inputImageHeight,
                    inputImageWidth,
                    ResizeOp.ResizeMethod.NEAREST_NEIGHBOR
                )
            )
            .add(NormalizeOp(0.0F, 255.0F))
            .build()

        var tImage = TensorImage(DataType.FLOAT32)

        tImage.load(bitmap)
        tImage = imageProcessor.process(tImage)
        bitmap.recycle()

        return tImage
    }

    /**
     * 모델이 출력한 ByteBuffer 를 마스킹 된 이미지 형태로 변환하는 동작 수행
     */
    private fun convertByteBufferMaskToBitmap(
        inputBuffer: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        backgroundImage: Bitmap,
        colors: IntArray
    ): Triple<Bitmap, Bitmap, Map<String, Int>> {
        val conf = Bitmap.Config.ARGB_8888
        val maskBitmap = Bitmap.createBitmap(imageWidth, imageHeight, conf)
        val resultBitmap = Bitmap.createBitmap(imageWidth, imageHeight, conf)
        val mSegmentBits = Array(imageWidth) { IntArray(imageHeight) }
        val itemsFound = HashMap<String, Int>()
        inputBuffer.rewind()

        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                var maxVal = 0f
                mSegmentBits[x][y] = 0

                for (c in 0 until NUM_CLASSES) {
                    val value = inputBuffer
                        .getFloat((y * imageWidth * NUM_CLASSES + x * NUM_CLASSES + c) * 4)
                    if (c == 0 || value > maxVal) {
                        maxVal = value
                        mSegmentBits[x][y] = c
                    }
                }
                val label = labelsArrays[mSegmentBits[x][y]]
                val color = colors[mSegmentBits[x][y]]
                itemsFound.put(label, color)
                val newPixelColor = ColorUtils.compositeColors(
                    colors[mSegmentBits[x][y]],
                    backgroundImage.getPixel(x, y)
                )
                resultBitmap.setPixel(x, y, newPixelColor)
                maskBitmap.setPixel(x, y, colors[mSegmentBits[x][y]])
            }
        }

        return Triple(resultBitmap, maskBitmap, itemsFound)
    }


    companion object {
        private const val TAG = "ImageSegmentation"
        private const val MODEL_FILE = "model.tflite"
        private const val FLOAT_TYPE_SIZE = 4
        private const val PIXEL_SIZE = 1
        private const val NUM_CLASSES = 7
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        val segmentColors = IntArray(NUM_CLASSES)

        val labelsArrays = arrayOf(
            "background", "sidewalk", "caution", "crosswalk", "guide_block",
            "alley", "sidewalk_damaged"
        )

        init {
            val random = Random(System.currentTimeMillis())
            segmentColors[0] = Color.TRANSPARENT
            for (i in 1 until NUM_CLASSES) {
                segmentColors[i] = Color.argb(
                    (128),
                    getRandomRGBInt(
                        random
                    ),
                    getRandomRGBInt(
                        random
                    ),
                    getRandomRGBInt(
                        random
                    )
                )
            }
        }

        private fun getRandomRGBInt(random: Random) = (255 * random.nextFloat()).toInt()
    }
}