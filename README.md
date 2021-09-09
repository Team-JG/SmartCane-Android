# 💡 Topic

- **딥 러닝 및 컴퓨터 비전 기반 시각 장애인 보행 보조장치**
- 사물인터넷학과 졸업작품 (캡스톤 디자인)
- 교내 캡스톤 디자인 경진대회 대상 수상
- 제 9회 K-Hackathon 결선 진출 (진행 중, 최소 장려상 확보)

# 📝 Summary

기존 보행 보조장치들의 장점만을 한데 모아 시각 장애인들이 더욱 안전하게 보행할 수 있도록 도와주는 지팡이 형태의 보행 보조장치입니다. 다양한 청각 및 촉각 피드백을 제공하고, 센서와 딥 러닝 모델이 시각 장애인의 눈을 대신하여 시각 장애인들의 안전성을 신장합니다. 지팡이와 목에 거는 스마트폰이 유기적으로 함께 동작합니다.

# ⭐️ Key Function

![위드미_동작설명](https://user-images.githubusercontent.com/30336663/132729244-382f99c6-40ad-407a-920b-063b390a11fb.png)

- 스마트폰이 촬영하고 있는 전방에 **보행 위험 요소**가 발견되면 **TTS 를 통한 청각 피드백** 제공
- **낙상 사고**가 발생한 경우, **사용자의 현재 위치를 기반으로 SOS 호출**

# 🛠 Tech Stack

`Kotlin`, `JetPack`, `DataBinding`, `ViewModel`, `AAC`, `LiveData`, `TF Lite`, `Gson`, `Retrofit`, `OkHttp`, `Koin`, `CameraX`, `Timber`, `Glide`, `GeoCoder`, `RxJava`, `Coroutine`, `Google TTS`

# ⚙️ Architecture

`MVVM`

# 🧑🏻‍💻 Team

- 안드로이드 개발자 1명
- 아두이노 개발자 1명
- 백엔드 개발자 1명

# 🤚🏻 Part

- **안드로이드 앱 전체 개발**
- **딥 러닝 모델 커스터마이징 및 모바일 앱 이식 (TF Lite)**
- 시스템 아키텍처 설계
- 아두이노 기능 설계, 안드로이드 앱 연동 기능
- 3D 모델링 및 폼 팩터 설계

# 🤔 Learned

- **`Coroutine Flow`** 를 통해 **비동기적 API 호출**을 구현하는 방법을 익히게 되었음.
- **`Broadcast Receiver`** 를 통해 **블루투스** 기기 **자동 연결 동작**을 구현하는 방법을 알게 되었음.
- **`TensorFlow Lite`** 모델을 앱에 이식하는 방법을 알게 되었음.
- TF Lite 모델 이용을 위한 **`Interpreter`** 작성법, **`NNAPI Delegate`** 사용법 등을 알게 되었음.
- 이론으로만 배웠던 **딥 러닝 모델** (Semantic Segmentation) 의 **동작 및 추론 원리**를 알게 되었음.
- **TensorFlow** 예제 소스코드 Repo 의 오탈자를 발견하여 **오픈소스 컨트리뷰팅**을 해볼 수 있었음.
- **`Camera X`** 를 통해 카메라 리소스를 사용하는 방법을 알게 되었음.
- **`GeoCoder`** 를 통해 사용자의 **현재 위치를 주소 형태**로 가져오는 방법을 익힐 수 있었음.
