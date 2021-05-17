package com.just_graduate.smartcane.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.*
import com.just_graduate.smartcane.util.Util
import com.just_graduate.smartcane.Repository
import com.just_graduate.smartcane.data.SegmentationResult
import com.just_graduate.smartcane.util.*
import okhttp3.MultipartBody
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
    val _segmentationResult = MutableLiveData<SegmentationResult>()
    val segmentationResult: LiveData<SegmentationResult>
        get() = _segmentationResult

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
     */
    fun getSegmentationResult(image: MultipartBody.Part) {
        // Bitmap -> MultipartBody.Part
        addDisposable(
            repository.getSegmentationResult(image = image)
                .applySchedulers()
                .subscribe(
                    {
                        _segmentationResult.value = it
                        Log.d("TEST", it.result)
                    },
                    {
                        Log.e("TEST ERROR", it.message.toString())
                    }
                )
        )
    }

}