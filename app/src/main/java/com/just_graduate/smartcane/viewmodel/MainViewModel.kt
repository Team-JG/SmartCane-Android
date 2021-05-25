package com.just_graduate.smartcane.viewmodel

import android.util.Log
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.*
import com.just_graduate.smartcane.util.Util
import com.just_graduate.smartcane.Repository
import com.just_graduate.smartcane.data.SegmentationResult
import com.just_graduate.smartcane.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MultipartBody
import timber.log.Timber
import java.nio.charset.Charset

class MainViewModel(private val repository: Repository) : BaseViewModel() {
    val connected: LiveData<Boolean?>
        get() = repository.connected
    val progressState: LiveData<String>
        get() = repository.progressState

    var btnConnected = ObservableBoolean(false)
    var inProgressView = ObservableBoolean(false)
    var txtProgress: ObservableField<String> = ObservableField("")

    private val _requestBleOn = MutableLiveData<Event<Boolean>>()
    val requestBleOn: LiveData<Event<Boolean>>
        get() = _requestBleOn

    val inProgress: LiveData<Event<Boolean>>
        get() = repository.inProgress

    val connectError: LiveData<Event<Boolean>>
        get() = repository.connectError

    val txtRead: ObservableField<String> = ObservableField("")
    val putTxt: LiveData<String>
        get() = repository.putTxt

    // 딥 러닝 API 호출 결과 받을 시 변화하는 LiveData
    private val _segmentationResult = MutableLiveData<SegmentationResult>()
    val segmentationResult: LiveData<SegmentationResult>
        get() = _segmentationResult

    // 낙상 감지 관련
    val isFallDetected: LiveData<Boolean>
        get() = repository.isFallDetected

    private lateinit var countDownJob: Job
    val count: MutableLiveData<Int> = MutableLiveData(20)

    fun setInProgress(en: Boolean) {
        repository.inProgress.value = Event(en)
    }

    /**
     * 블루투스 연결 버튼 눌렀을 때
     */
    fun onClickConnect() {
        if (connected.value == false || connected.value == null) {
            if (repository.isBluetoothSupport()) {
                if (repository.isBluetoothEnabled()) { // 블루투스가 활성화 되어있다면
                    // Progress View.VISIBLE
                    setInProgress(true)

                    // 지팡이 스캔 시작
                    repository.scanDevice()
                } else {
                    // 블루투스를 활성 상태로 바꾸기 위해 사용자 동의 요청
                    _requestBleOn.value = Event(true)
                }
            } else { //블루투스 지원 불가
                Util.showToast("Bluetooth is not supported.")
            }
        } else {
            repository.disconnect()
        }
    }

    /**
     * 블루투스 리시버 등록 해제
     */
    fun unregisterReceiver() {
        repository.unregisterReceiver()
    }

    /**
     * Data Binding 을 통해 파라미터에 EditText 값이 담김
     */
    fun onClickSendData(command: Int) {
        val commandString = command.toString()
        val byteArr = commandString.toByteArray(Charset.defaultCharset())
        repository.sendByteData(byteArr)
    }

    /**
     * 찍힌 이미지에 대하여 Segmentation Result 요청
     * - Django 서버 API 통해서 결과 얻음
     * - Django 서버에 pspunet 기반의 인도 보행 이미지 분석 모델 탑재
     * - Coroutines Flow 를 활용한 비동기 스트림
     */
    fun getSegmentationResult(image: MultipartBody.Part) {
        val data = flow { emit(repository.getSegmentationResult(image = image)) }
        viewModelScope.launch {
            data.catch { Timber.i(it) }
                .flowOn(Dispatchers.IO)
                .collect {
                    Timber.d(it.result)
                    _segmentationResult.value = it
                }
        }
    }


    private fun initCountDownJob() {
        countDownJob = Job()
    }

    fun doCountDownJob() {
        initCountDownJob()
        val coroutineName = countDownJob.toString().split("{")
        val coroutineStatus = coroutineName[1].substring(0, 6)
        if (coroutineStatus == "Active") {
            cancelCountDownJob()
        } else {
            viewModelScope.launch(countDownJob){
                decreaseCount()  // 카운트 다운 시작
            }
        }
    }

    /**
     * Coroutines 로 20초 카운트 다운
     */
    private suspend fun decreaseCount() {
        for (i in 20 downTo 1) {
            count.value = count.value?.minus(1)
            Timber.d(count.value.toString())
            delay(1000L)
        }
        // 20초 카운트 다운 종료
        Timber.d("CountDown Cleared!")
    }

    /**
     * 만약 지팡이를 다시 쥐었다는 신호를 받으면
     * CountDownJob 종료 cancel()
     */
    private fun cancelCountDownJob() {
        if (countDownJob.isActive || countDownJob.isCompleted) {
            count.value = 20
            countDownJob.cancel()
        }
    }

}