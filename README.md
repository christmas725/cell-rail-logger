# Cell Rail Logger v0.1

대경선/대구 도시철도 2호선에서 **GPS 좌표 없이 이동통신 셀 정보의 반복 패턴**을 수집하기 위한 Android 실험 앱입니다.

## v0.1 목표

이 버전에서는 위치를 추정하지 않습니다. 먼저 같은 구간을 여러 번 주행했을 때 LTE/5G 셀 전환 패턴이 얼마나 반복되는지 검증합니다.

- 노선 선택: 대경선 / 대구 도시철도 2호선
- 방향 선택
- 시작역 선택
- `TelephonyCallback.CellInfoListener`로 셀 변화 수신
- `requestCellInfoUpdate()`로 3초 간격의 최신 셀 정보 갱신 요청
- LTE/5G의 Cell ID/NCI, TAC, PCI, EARFCN/NRARFCN, RSRP, RSRQ, SINR 기록
- 등록 셀뿐 아니라 관측되는 셀을 함께 기록
- 역 출발/도착 수동 마커
- CSV 저장 및 Storage Access Framework로 내보내기
- 기록 중 화면 꺼짐 방지

## 중요한 개인정보/권한 설계

앱은 `LocationManager`, Fused Location Provider, GPS 위도/경도를 호출하거나 저장하지 않습니다.

다만 Android 정책상 셀 식별자 접근 자체에 `ACCESS_FINE_LOCATION`이 필요하므로 다음 권한은 요청합니다.

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `READ_PHONE_STATE`

v0.1에는 다음 권한을 넣지 않았습니다.

- `INTERNET`
- `ACCESS_BACKGROUND_LOCATION`

따라서 로그는 기기 내부에만 저장됩니다.

## 테스트 프로토콜

같은 휴대폰, 같은 SIM/eSIM, 같은 네트워크 모드(예: 5G 우선)를 유지한 채 같은 방향의 동일 구간을 3~5회 이상 기록하는 것을 권장합니다.

1. 승차 후 앱 실행
2. 노선/방향/시작역 선택
3. `기록 시작`
4. 문이 닫히고 열차가 움직이기 시작할 때 `현재 역 출발`
5. 다음 역에 완전히 정차하고 문이 열릴 때 `다음 역 도착`
6. 종착 또는 원하는 구간이 끝나면 `기록 종료`
7. `마지막 CSV 내보내기`로 파일 저장

마커 시점을 매번 같은 기준으로 맞추는 것이 중요합니다.

## 대경선 역 데이터 (2026-09 기준)

구미 → 사곡 → 북삼 → 왜관 → 서대구 → 대구 → 동대구 → 경산

북삼역은 2026-02-28부터 정식 운행을 시작한 현재 역으로 포함했습니다.

## 다음 단계

CSV가 3~5회 정도 모이면 다음을 계산할 예정입니다.

1. 구간별 등록 셀 전환 sequence
2. 선택적/일시적 셀을 제외한 stable fingerprint
3. 각 셀/PCI의 체류시간 분포
4. RSRP/RSRQ/SINR 변화 곡선
5. 같은 구간 반복 주행의 패턴 일치율
6. 방향 판별 가능성

패턴 재현율이 충분하면 v0.2에서 `route fingerprint matcher`와 현재 구간 자동 판별을 추가합니다.

## 빌드 기준

- Android Studio Quail 4 (2026.1.4) 이상 권장
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0 호환
- compileSdk/targetSdk 37
- minSdk 31

