# VPOS BLE

Android POS 기기용 BLE (Bluetooth Low Energy) 데모 애플리케이션

## 개요

이 프로젝트는 POS 기기에서 고객의 스마트폰과 BLE를 통해 통신하는 데모 앱입니다.

## 기능

- **BLE 스캔**: 주변 BLE 디바이스 검색
- **GATT 서버**: POS 기기가 Peripheral 역할로 동작하여 고객 폰의 연결 수락
- **양방향 통신**: 디바이스 간 메시지 송수신
- **실시간 로그**: 통신 상태 및 이벤트 로그 표시

## 프로젝트 구조

```
app/src/main/java/com/example/vposble/
├── MainActivity.java          # 메인 액티비티
├── ble/
│   ├── BleManager.java        # BLE 클라이언트 (Central) 관리
│   └── GattServerManager.java # GATT 서버 (Peripheral) 관리
└── ui/
    └── DeviceAdapter.java     # 디바이스 목록 어댑터
```

## BLE UUID

- Service UUID: `0000fff0-0000-1000-8000-00805f9b34fb`
- Characteristic UUID: `0000fff1-0000-1000-8000-00805f9b34fb`

## 요구사항

- Android SDK 23 (Marshmallow) 이상
- BLE 지원 디바이스
- 위치 권한 (Android 11 이하)
- Bluetooth 권한 (Android 12 이상)

## 빌드

```bash
./gradlew assembleDebug
```

## 사용법

1. **서버 모드 (POS 기기)**
   - "Start Server" 버튼을 눌러 GATT 서버 시작
   - 고객 폰에서 연결 대기

2. **클라이언트 모드**
   - "Scan Devices" 버튼으로 주변 디바이스 검색
   - 목록에서 디바이스 선택하여 연결

3. **메시지 전송**
   - 텍스트 입력 후 "Send" 버튼 클릭

## 라이선스

MIT License
