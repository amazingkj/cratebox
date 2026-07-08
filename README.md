# cratebox

중소 기획사·인디 레이블·소규모 음반 유통사를 위한 음반 도메인 특화 재고·정산 시스템.

- **명세**: [docs/SPEC.md](docs/SPEC.md)
- **데이터 모델 (사입/위탁 통합 원장)**: [docs/DATA-MODEL.md](docs/DATA-MODEL.md)

## 구성

| 디렉터리 | 내용 |
|---|---|
| `server/` | Java 21 + Spring Boot 3.5, Spring Data JDBC + Flyway. 원장·정산 엔진 |
| `web/` | React + TypeScript + Vite + Tailwind. 빌드 결과는 server 정적 리소스로 들어가 단일 배포물 |
| `docker/` | Postgres 초기화 스크립트 |

## 실행

```bash
# 1. DB (Postgres 16, 포트 5433 — 5432는 다른 컨테이너가 쓰는 환경 기준)
docker compose up -d

# 2. 백엔드 (http://localhost:8087)
cd server && ./gradlew bootRun

# 3. 로그인: admin / admin1234!  (환경변수 CRATEBOX_ADMIN_PASSWORD로 변경)
```

**폰에서 현장판매(카메라 스캔)**: 브라우저 카메라는 https에서만 열린다. 같은 서버가
`https://<PC IP>:8443` 도 함께 서빙하므로(자체서명 인증서) 폰에서 이 주소로 접속해
경고를 한 번 승인하면 된다. 비활성화: `cratebox.https.enabled=false`.

자체서명 키스토어는 커밋되지 않는다 — 클론 후 아래 한 줄로 생성한다 (없으면 https 커넥터만
건너뛰고 http 8087은 정상 동작):

```bash
keytool -genkeypair -alias cratebox -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore server/src/main/resources/tls/cratebox-dev.p12 -validity 3650 \
  -storepass cratebox-dev -dname "CN=cratebox-dev"
```

프런트 개발 모드(핫리로드): `cd web && npm run dev` → http://localhost:5173 (API는 8087로 프록시)
프런트 번들 갱신: `cd web && npm run build` 후 백엔드 재시작.

## 테스트

```bash
cd server && ./gradlew test
```

`GoldenScenarioIT`(①): 한 달치 운영(입고→출고→진열→판매보고→반품→이동→실사→회수→매입반품→입금→마감)을
실제 API로 수행하고 손으로 계산한 정산서 숫자와 전액 대조한다. 원장 불변식(엔트리 합 == 잔량 캐시),
append-only 트리거, 마감후 반품 이월/소급, VAT 절사까지 검증.

`ConsignmentScenarioIT`(② 위탁, 별도 org): 수탁입고→위탁 판매 이중 전기(수수료 절사)→반납→MG 마감
차감(앨범 단위 회수)→역분개→소유 풀 분리(자사/위탁)→기획사 포털 스코프(자기 데이터만, 운영 API 403)→
현장판매(자사 정산 없음·위탁 기획사몫)→발행사 정보→비밀번호 변경·재설정을
손계산과 대조한다. DB는 `cratebox_test`를 매 실행 시 clean+migrate.

로그인 역할: `admin`(운영자) 외에, 거래처·기획사 화면에서 기획사당 1개의 **파트너 포털 계정**(읽기전용)을
발급할 수 있다.
