package com.just_graduate.smartcane

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.location.LocationListener
import android.location.LocationManager
import android.telephony.SmsManager
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.maps.model.LatLng
import com.just_graduate.smartcane.Constants.DEVICE_NAME
import com.just_graduate.smartcane.Constants.SPP_UUID
import com.just_graduate.smartcane.network.NetworkHelper.retrofitService
import com.just_graduate.smartcane.network.RetrofitService
import com.just_graduate.smartcane.util.Event
import com.just_graduate.smartcane.util.Util
import com.just_graduate.smartcane.util.Util.textToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import okhttp3.MultipartBody
import timber.log.Timber
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class Repository : RetrofitService {
    var connected: MutableLiveData<Boolean?> = MutableLiveData(null)
    var progressState: MutableLiveData<String> = MutableLiveData("")

    val putTxt: MutableLiveData<String> = MutableLiveData("")

    val inProgress = MutableLiveData<Event<Boolean>>()
    val connectError = MutableLiveData<Event<Boolean>>()

    var mBluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var mBluetoothStateReceiver: BroadcastReceiver? = null

    var targetDevice: BluetoothDevice? = null

    var socket: BluetoothSocket? = null
    var mOutputStream: OutputStream? = null
    var mInputStream: InputStream? = null

    var foundDevice: Boolean = false

    // 낙상 감지 관련
    val isFallDetected: MutableLiveData<Boolean> = MutableLiveData(false)

    // 사용자 현재 위치 관련
    private lateinit var locationManager: LocationManager
    private lateinit var context: Context
    val currentAddress: MutableLiveData<String> = MutableLiveData("")

    companion object {
        const val MIN_TIME_MS = 5000L
        const val MIN_DISTANCE_METER = 1f
    }

    /**
     * 사용자 위치 (위,경도) 가 바뀔 때 마다 호출되는 onLocationChanged() 리스너
     */
    private val locationListener =
        LocationListener {
            Timber.d("Location : ${it.longitude}")
            it.let {
                val position = LatLng(it.latitude, it.longitude)
                Timber.d("LATITUDE : ${position.latitude} and LONGITUDE : ${position.longitude}")
                getAddress(position)
            }
        }

    private fun getAddress(position: LatLng) {
        val geoCoder = Geocoder(context, Locale.getDefault())
        val address = geoCoder.getFromLocation(position.latitude, position.longitude, 1).first()
            .getAddressLine(0)
        currentAddress.value = address
        Timber.d("ADDRESS : ${address}")
        Timber.d("ADDRESS : ${currentAddress.value}")
    }

    /**
     * MainActivity Init 시점에서 context 전달 받아 LocationManager 초기화
     * - PermissionGranted 확인 상태에서 호출했으므로 SuppressLint 추가
     */
    @SuppressLint("MissingPermission")
    fun initLocationManager(context: Context) {
        Timber.d("Location Manager Init")

        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        this.context = context

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            MIN_TIME_MS,
            MIN_DISTANCE_METER,
            locationListener
        )
        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            MIN_TIME_MS,
            MIN_DISTANCE_METER,
            locationListener
        )
    }

    /**
     * 딥 러닝 서버 API (Image Segmentation) 를 호출하기 위한 Retrofit Service 메소드 실행
     */
    override fun getImageSegmentationResult(image: MultipartBody.Part) =
        retrofitService.getImageSegmentationResult(image = image)

    /**
     * 블루투스 지원 여부
     */
    fun isBluetoothSupport(): Boolean {
        return if (mBluetoothAdapter == null) {
            Util.showToast(BaseApplication.applicationContext().getString(R.string.bt_disable))
            textToSpeech(BaseApplication.applicationContext().getString(R.string.bt_disable))
            false
        } else {
            true
        }
    }

    /**
     * 블루투스 ON/OFF 여부
     */
    fun isBluetoothEnabled(): Boolean {
        return if (!mBluetoothAdapter!!.isEnabled) {
            // 블루투스를 지원하지만 비활성 상태인 경우
            // 블루투스를 활성 상태로 바꾸기 위해 사용자 동의 요청
            Util.showToast(BaseApplication.applicationContext().getString(R.string.plz_able_bt))
            textToSpeech(BaseApplication.applicationContext().getString(R.string.plz_able_bt))
            false
        } else {
            true
        }
    }

    /**
     * 지팡이 기기 스캔 동작
     */
    @ExperimentalUnsignedTypes
    fun scanDevice() {
        progressState.postValue("지팡이 스캔 중")
        textToSpeech(BaseApplication.applicationContext().getString(R.string.finding_cane))

        registerBluetoothReceiver()  // BroadcastReceiver 인스턴스 생성

        val bluetoothAdapter = mBluetoothAdapter
        foundDevice = false
        bluetoothAdapter?.startDiscovery() // 지팡이 스캔 시작
    }

    /**
     * 블루투스 리시버 등록
     */
    @ExperimentalUnsignedTypes
    private fun registerBluetoothReceiver() {
        val stateFilter = IntentFilter()
        stateFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED) // BluetoothAdapter.ACTION_STATE_CHANGED : 블루투스 상태변화 액션
        stateFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        stateFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED) // 연결 확인
        stateFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED) // 연결 끊김 확인
        stateFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        stateFilter.addAction(BluetoothDevice.ACTION_FOUND) // 기기 검색됨
        stateFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED) // 기기 검색 시작
        stateFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) // 기기 검색 종료
        stateFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)

        mBluetoothStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val action = intent.action // 입력된 action
                if (action != null) {
                    Log.d("Bluetooth action", action)
                }
                val device =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                var name: String? = null
                if (device != null) {
                    name = device.name // Broadcast 를 보낸 기기의 이름을 가져옴
                }
                when (action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR
                        )
                        when (state) {
                            BluetoothAdapter.STATE_OFF -> {
                            }
                            BluetoothAdapter.STATE_TURNING_OFF -> {
                            }
                            BluetoothAdapter.STATE_ON -> {
                            }
                            BluetoothAdapter.STATE_TURNING_ON -> {
                            }
                        }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        connected.postValue(false)
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        if (!foundDevice) {
                            val deviceName = device!!.name

                            // "SmartCane" 이라는 기기만 연결
                            if (deviceName != null && deviceName.length > 3) {
                                if (deviceName == DEVICE_NAME) {
                                    targetDevice = device
                                    foundDevice = true
                                    textToSpeech(context!!.getString(R.string.try_connect_cane))
                                    connectToTargetedDevice(targetDevice)
                                }
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (!foundDevice) {
                            Util.showToast(context!!.getString(R.string.cannot_find_cane))
                            textToSpeech(context.getString(R.string.cannot_find_cane))
                            inProgress.postValue(Event(false))
                        }
                    }
                }
            }
        }
        BaseApplication.applicationContext().registerReceiver(
            mBluetoothStateReceiver,
            stateFilter
        )
    }

    /**
     * 스캔한 지팡이 기기와 블루투스 연결 (Coroutine 구현)
     */
    @ExperimentalUnsignedTypes
    private fun connectToTargetedDevice(targetedDevice: BluetoothDevice?) {
        progressState.postValue("${targetDevice?.name}에 연결중..")

        CoroutineScope(Default).launch {
            //선택된 기기의 이름을 갖는 bluetooth device의 object
            val uuid = UUID.fromString(SPP_UUID)
            try {
                // 소켓 생성
                socket = targetedDevice?.createRfcommSocketToServiceRecord(uuid)
                socket?.connect()

                connected.postValue(true)
                mOutputStream = socket?.outputStream
                mInputStream = socket?.inputStream

                // 아두이노로부터 오는 데이터 수신 (지팡이 낙상 감지 등)
                beginListenForFallDetection()

            } catch (e: java.lang.Exception) {
                // 블루투스 연결 중 오류 발생
                e.printStackTrace()
                connectError.postValue(Event(true))
                try {
                    socket?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 블루투스 연결 해제
     */
    fun disconnect() {
        try {
            socket?.close()
            connected.postValue(false)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * 블루투스 리시버 등록 해제
     */
    fun unregisterReceiver() {
        if (mBluetoothStateReceiver != null) {
            BaseApplication.applicationContext().unregisterReceiver(mBluetoothStateReceiver)
            mBluetoothStateReceiver = null
        }
    }

    /**
     * 블루투스 데이터 송신
     */
    fun sendByteData(data: ByteArray) {
        CoroutineScope(Default).launch {
            try {
                mOutputStream?.write(data) // 프로토콜 전송
            } catch (e: Exception) {
                // 문자열 전송 도중 오류가 발생한 경우.
                e.printStackTrace()
            }
        }
    }

    /**
     * Convert
     * @ByteToUint : byte[] -> uint
     * @byteArrayToHex : byte[] -> hex string
     */
    private val m_ByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(8)

    // Byte -> uInt
    fun ByteToUint(data: ByteArray?, offset: Int, endian: ByteOrder): Long {
        synchronized(m_ByteBuffer) {
            m_ByteBuffer.clear()
            m_ByteBuffer.order(endian)
            m_ByteBuffer.limit(8)
            if (endian === ByteOrder.LITTLE_ENDIAN) {
                m_ByteBuffer.put(data, offset, 4)
                m_ByteBuffer.putInt(0)
            } else {
                m_ByteBuffer.putInt(0)
                m_ByteBuffer.put(data, offset, 4)
            }
            m_ByteBuffer.position(0)
            return m_ByteBuffer.long
        }
    }

    fun byteArrayToHex(a: ByteArray): String? {
        val sb = StringBuilder()
        for (b in a) sb.append(String.format("%02x ", b /*&0xff*/))
        return sb.toString()
    }

    /**
     * 아두이노 블루투스 데이터 수신 Listener 생성 (Thread 실행)
     */
    @ExperimentalUnsignedTypes
    fun beginListenForFallDetection() {
        val mWorkerThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val bytesAvailable = mInputStream?.available()
                    if (bytesAvailable != null) {
                        if (bytesAvailable > 0) { // 블루투스 통신 데이터가 수신된 경우

                            /**
                             * 한 바이트에 대해서만 처리 (아두이노 단에서 1 바이트씩만 보냄)
                             * - 01 : 지팡이를 놓친 상황
                             * - 02 : 지팡이를 놓쳤다가 다시 잡은 상황
                             */
                            val packetBytes = ByteArray(bytesAvailable)
                            mInputStream?.read(packetBytes)
                            val data = packetBytes[0]

                            // 낙상 감지 관련 데이터에 대응하는 동작을 수행하는 메소드 호출
                            handleFallDetectionData(data)
                        }
                    }
                } catch (e: UnsupportedEncodingException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        // 블루투스 데이터 수신 thread 시작
        mWorkerThread.start()
    }

    /**
     * 아두이노가 보낸 낙상감지 관련 데이터를 핸들링하는 메소드
     * - 01 : 지팡이를 놓친 상황 => 지팡이를 다시 잡으라는 TTS 발생 후 20초 카운트 다운 ( TODO 카운트 다운 종료 후 SOS 호출 )
     * - 02 : 지팡이를 놓쳤다가 다시 잡은 상황 => 카운트 다운을 멈추고 안전 보행하라는 TTS 발생
     */
    private fun handleFallDetectionData(data: Byte) {
        Log.d("inputData", String.format("%02x", data))
        Log.d("inputData", data.toString())

        // 지팡이를 놓쳤다는 신호를 받았을 때
        if (String.format("%02x", data) == "01") {
            textToSpeech("지팡이를 놓쳤습니다. 지팡이를 다시 잡지 않으면 20초 후 SOS 호출을 합니다")
            isFallDetected.value = true
        }

        // 지팡이를 다시 잡았다는 신호를 받았을 때
        if (String.format("%02x", data) == "02") {
            textToSpeech("지팡이를 다시 잡으셨군요. 안전 보행 하세요.")
            isFallDetected.value = false
        }
    }

    /**
     * 긴급 SOS 호출을 하는 메세지 전송을 위한 메소드
     * - 낙상 지 20초 카운트 다운이 끝났을 때 발동
     */
    private fun sendSMS(){
        val addressText = currentAddress.value
        val smsText = "${addressText} -> 위치에서 시각 장애인인 제가 낙상되었습니다. 응급 출동 바랍니다."
        val smsManager = SmsManager.getDefault()
        smsManager.sendTextMessage("01023813473", null, smsText, null, null)
    }


}