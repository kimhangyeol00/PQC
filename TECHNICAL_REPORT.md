# PQC 해커톤 예선 기술보고서 초안

## 표지 정보

- 문제: 협력사 계약 문서함의 PQC 전환
- 팀명: 미기재 (개인 참가 시 ‘개인’으로 수정)
- 성명: 미기재
- 버전: `problem_source.zip` 기반 구현 / 2026-11-07

> 문제 플랫폼에서 받은 공식 양식은 별도 파일 `technical-report-template.hwp`로 함께 두었습니다. 이 Markdown 보고서는 제출 버전의 기술 내용 원본이며, 제출 전에 표지의 참가자 정보를 채우고 공식 양식에 옮겨 쪽수·서식을 확인해야 합니다. 양식의 표지 제외 7쪽 이내 조건에 맞춰 아래 내용을 요약형으로 작성했습니다.

## 1. 수행 결과 요약

기존 문서함은 문서 본문을 AES-256-GCM으로 암호화하고 데이터 키를 작성자·수신자 RSA-2048 공개키로 각각 RSA-OAEP-SHA-256 포장했으며, 승인은 ECDSA P-256/SHA-256으로 서명했습니다. 새 문서·의견은 작성자와 수신자별 ML-KEM-768 캡슐화로 공유 비밀을 만들고 Bouncy Castle KTS의 KDF로 256-bit AES KEK를 파생해 문서 DEK를 GCM 포장합니다. 본문은 AES-256-GCM으로 보호합니다. 최신 승인에는 ML-DSA-65 서명을 적용하며 계약·버전 문맥 및 봉투 전체의 SHA-512 지문을 서명합니다.

```text
작성자 ──작성/수정──> 계약 서비스 ──AES-256-GCM 봉투──> SQLite
                              │                            │
                ML-KEM-768 ──┴── 작성자 키 / 수신자 키 ───┘
                              │
수신자 ──전달 문서 열람/의견──┤
                              └──최신 버전 승인── ML-DSA-65 서명
                                   │
                            승인본 확인 시 서명·봉투 재검증
```

문서 작성·전달·열람·의견·승인·승인본 확인과 사용자별 권한 흐름을 유지했습니다. 기존 저장소에서 과거 RSA/ECDSA 자료는 계속 읽고 확인하되, 새 자료는 PQC 봉투와 서명만 사용합니다. ML-KEM/ML-DSA 처리, AEAD 태그 또는 서명 검증에 실패하면 오류를 반환하는 fail-closed 경로를 적용했습니다. JUnit 테스트 5개와 HTTP API smoke에서 정상 업무 흐름, 레거시 전환, 재시작 후 지속성, 비권한 사용자 거부, 암호문 변경 거부를 확인했습니다. 테스트가 확인하는 `X-User` 헤더는 실습용 신원 선택값일 뿐 인증 수단이 아니므로 실제 배포 권한경계로 간주하면 안 됩니다.

## 2. 수행 목표 및 범위

목표는 장기 보관 계약 본문과 승인 진위를 양자컴퓨터 위협에 대비시키면서 기존 API 및 업무 흐름을 유지하는 것입니다. 전환 범위는 본문 봉투의 공개키 기반 키 전달, 승인 서명과 검증, 새 의견의 저장 암호화입니다. 대칭 데이터 암호 AES-256-GCM, 저장 DB(SQLite), 업무 상태·라우팅 로직은 유지했습니다. 네트워크 TLS, 사용자 인증, 키 관리 서비스 및 HSM은 제공 소스에 구성되어 있지 않아 이번 구현 범위 밖입니다.

## 3. 기존 서비스 구조 및 암호 자산 인벤토리

분석 근거는 `src/main/java/com/partner/contracts/DocumentProtection.java`, `CredentialStore.java`, `ContractService.java`, `ApiController.java`, `Storage.java`, `src/main/resources/schema.sql`, `application.properties`, `compose.yaml` 및 정적 화면의 사용자 선택·API 호출입니다. 흐름은 작성자가 계약을 만들거나 새 버전을 저장하고, 암호화 봉투를 SQLite `versions.envelope`에 기록합니다. 작성자는 특정 검토자에게 버전을 전달하고, 검토자는 전달된 버전을 열람·의견 등록 후 최신 버전을 승인합니다. `approvals`에는 승인자, 시각, 방식, 서명이 남습니다. 본문은 접근 시 서버가 사용자별 개인키로 풀어 API 응답에 돌려줍니다.

| 자산/구성요소 | 소스·저장 근거 | 기존 기능·키 | 민감도/위험 | 전환 상태 |
|---|---|---|---|---|
| 계약 제목·본문·버전 | `versions.title`, `versions.envelope`; `DocumentProtection.protect/open` | AES-256-GCM, 랜덤 256-bit DEK, 기존 RSA-2048 OAEP-SHA-256 DEK 포장. 신규는 ML-KEM-768 공유 비밀→KTS 파생 AES-256 KEK→GCM DEK 포장 | 매우 높음. 계약 조항·기술자료이며 장기 보관·수집 후 복호화 위험 | AES 유지, 공개키 기반 DEK 전달을 ML-KEM-768/KTS로 전환 |
| 계약 의견 | `comments.content`; 원본은 평문 SQLite 저장 | 별도 공개키 암호 없음 | 높음. 의견에 계약 정보·협상 조건이 포함될 수 있음 | 새 의견을 AES-GCM+사용자별 ML-KEM 봉투로 보호 |
| 승인 증거 | `approvals`의 승인자·시각·알고리즘·서명, `ContractService.message` | ECDSA P-256/SHA-256; 계약/버전 문맥과 봉투 SHA-256 지문을 서명 | 매우 높음. 장기 진위·변경 탐지, 장기 검증 필요 | 새 승인 ML-DSA-65 + 봉투 SHA-512 지문 |
| 사용자 공개/개인키 | `CredentialStore`; `DATA_DIR/keys/*.pem`, `.pqc.pem`, `.pqc.pub.pem`; DB `metadata.pqc_keys_initialized` | 사용자별 RSA 암호화 키와 EC 서명 키 | 최고. 키 유출 시 기밀성 또는 승인 진위 붕괴 | 레거시 키 유지, ML-KEM/ML-DSA 별도 키쌍 생성·영구 보관. 초기 도입 완료 마커와 키쌍 자체검사로 유실·불일치 차단 |
| 업무 DB·전달/권한 메타데이터 | `contracts`, `deliveries`, `comments`, `approvals` SQLite 테이블 | 공개키 암호 없음. 전달 관계와 사용자 ID로 앱 권한 결정 | 높음. 관계·시점·승인 유무의 노출·변조 위험 | DB 자체 암호화는 미적용; 승인 메시지 서명은 승인 문맥과 버전 봉투 지문을 검증 |
| API/전송 계층 | `ApiController`, `application.properties`, `compose.yaml` | `X-User` 헤더 가상 사용자, 소스 내 TLS 미설정 | 인터넷 노출 시 전송·인증 위험 | 프로젝트 내 TLS·인증 전환 없음; 로컬 전용으로 제한 |

### 우선순위와 가정

1. **계약 본문·데이터 키 전달** — 민감도가 가장 높고 이미 저장·전달 봉투에 노출되므로 수집 후 복호화(HNDL)에 우선 대응했습니다. ML-KEM-768 캡슐화는 사용자마다 1,088-byte 암호문을 더하며 공개키는 1,184 bytes입니다(FIPS 203의 원시 인코딩 크기). 각 새 버전에 작성자와 수신자별 캡슐화가 기록됩니다.
2. **승인 서명·검증 키** — 승인본이 장기간 보관되면 ECDSA 위조 가능성은 승인 증거 전체를 약화시킵니다. 신규 승인에 ML-DSA-65를 적용하고 봉투 SHA-512 지문을 포함합니다. FIPS 204 원시 공개키는 1,952 bytes, 서명은 3,309 bytes로 ECDSA보다 커집니다.
3. **의견 본문** — 원본 구현에서 SQLite 평문이 확인되어 새 의견에 동일한 KEM 봉투 보호를 적용했습니다. 인덱싱·검색을 위해 의견 본문 평문을 저장하지 않습니다.
4. **TLS·실제 인증·중앙 키 운영** — 소스·실행 설정에서 TLS, 외부 IdP, KMS/HSM을 찾지 못했습니다. 문서 키를 PQC로 바꾸어도 네트워크 TLS의 양자내성, `X-User` 신뢰성, 서버 탈취 방지까지 해결하지 않습니다. 실제 외부 배포 시 다음 전환 단계로 다뤄야 합니다.

**가정:** 자료에는 보존기간, 법적 보존 의무, 외부 네트워크 경계, TLS 종단, 실제 계정 인증 및 운영 키 백업·복구 절차가 명시되지 않았습니다. 우선순위 산정에서는 계약 종료 뒤 10년까지 본문·승인본을 보관하고, 계약 정보는 그 기간 동안 높은 민감도를 유지하며, 현재 구성은 로컬·신뢰된 단일 서비스로만 쓰인다고 가정했습니다. 실제 기간이 더 짧아도 저장된 봉투의 HNDL 위험과 승인 진위 요구는 남습니다. 반대로 보존기간이 10년을 넘거나 인터넷에 노출되면 TLS/인증·KMS 전환과 감사 로그 우선순위를 더 높여야 합니다.

## 4. 전환 대상과 적용 설계

| 역할 | 적용 알고리즘/방식 | 실패 처리 |
|---|---|---|
| 본문·의견 암호화 | AES-256-GCM, 랜덤 12-byte nonce, 128-bit 태그; 문서 맥락은 AAD | 태그 오류·문맥 불일치 시 내용을 반환하지 않고 409 |
| 사용자별 데이터 키 전달 | ML-KEM-768 (FIPS 203) 단독 PQC KEM. 작성자·수신자 공개키마다 캡슐화하여 공유 비밀과 캡슐화 바이트를 생성. KTSParameterSpec(`AES`, 256)으로 KDF3/SHA-256을 적용해 AES-256 KEK를 파생하고, 이를 랜덤 nonce의 AES-GCM으로 DEK를 포장. 수신자 ID와 버전 문맥을 포장 AAD에 결합 | 알 수 없는 키·캡슐화·인증 오류 시 fail closed. RSA 폴백 없음 |
| 승인·승인본 무결성 | ML-DSA-65 (FIPS 204); 도메인 분리된 승인 메시지에 계약/버전 맥락, 승인자/시각, 봉투 SHA-512 지문 포함 | 지원하지 않는 서명 형식, 검증 실패, 봉투 변경 시 승인본 거부 |
| 공개키 공개 | PEM 인코딩 공개키를 기존 사용자 공개키 API에서 제공. 용도별 PQC 키 | 불일치·누락 시 키 처리 실패 |

PQC 단독을 택했습니다. 공인된 FIPS 203/204 매개변수의 직접 적용은 하나의 일관된 암호 문맥을 만들고, 하이브리드 키 결합 규칙·클래식 폴백 경로의 구현 오류를 줄입니다. 대신 ML-KEM/ML-DSA를 지원하지 않는 구형 클라이언트와는 호환되지 않으며, 라이브러리 결함 또는 격자 기반 분석 발전에 별도 클래식 방어층이 없습니다. ML-KEM-512보다 보수적인 768을 선택했고, 1024 대비 캡슐 크기·계산량을 낮추는 범주 3 선택입니다. NIST는 FIPS 203 매개변수에서 768을 192-bit 범주로 설명하며 더 높은 보안 세트일수록 성능이 낮아진다고 명시합니다.

**비교 대안:** ML-KEM-768과 X25519를 함께 사용해 독립 비밀을 결합하는 하이브리드 KEM은 전통적 보안과 양자내성을 함께 유지하는 전환기 선택지입니다. 알고리즘 하나의 약화 영향을 낮출 수 있지만, 결합 함수·키 확인·양쪽 실패 처리와 이중 키/캡슐 저장으로 구현·운영 복잡성이 커지고 전송량도 늘어납니다. 이 프로젝트에선 프로토콜·클라이언트 호환 계층이 별도로 없고 새 데이터의 PQC 보호가 필수라 PQC 단독으로 일관되게 전환했습니다. 대안은 설계 비교만 했고 구현·성능 측정은 하지 않았습니다.

## 5. 주요 변경사항과 구현 내용

- `pom.xml`: `spring-boot-starter-test`를 추가해 통합 검증을 빌드에 포함했습니다. 기존 Bouncy Castle 1.86 의존성을 사용하며 별도 암호 프록시·서비스를 추가하지 않았습니다.
- `Application`/`Storage`: 최초 PQC 도입을 `metadata.pqc_keys_initialized`로 표시합니다. 기존 RSA/EC 키와 DB만 있는 레거시 저장소이고 PQC 자산도 아직 없으면 RSA/EC 키 생성은 계속 금지하면서 PQC 키 생성만 허용합니다. 키 생성·self-test가 끝난 뒤 마커를 기록합니다. 마커가 있거나 PQC 자료가 발견된 경우에는 키 재생성을 금지합니다.
- `CredentialStore`: 사용자별 ML-KEM-768·ML-DSA-65 키를 새 `.pqc` PEM 파일로 생성하고 기존 RSA/EC 키와 분리해 유지합니다. 키 파일을 데이터 디렉터리에 보관하므로 DB와 동일한 영속 저장소를 사용합니다. 로드한 ML-DSA 쌍은 서명/검증, ML-KEM 쌍은 캡슐화/역캡슐화 self-test로 대응 여부를 확인합니다. 마커가 있는 저장소에서 키 누락·불완전·불일치 시 자동 재생성하지 않고 시작을 거부합니다. POSIX에선 개인키 0600, 폴더 0700 권한을 지정합니다.
- `DocumentProtection`: 새 `contract-envelope-v2`를 생성합니다. 작성자·지정 수신자 공개키별 ML-KEM 캡슐화로 KEM 공유 비밀과 캡슐화 바이트를 만들고, KTS가 파생한 AES-256 KEK로 DEK를 AES-GCM 포장합니다. envelope v1/RSA 복호화와 ECDSA 승인 확인은 이미 존재하는 데이터만 읽기 위한 호환 경로입니다.
- `ContractService`: 새 승인 메시지는 봉투 SHA-512 지문으로 ML-DSA-65 검증하며, 과거 ECDSA 기록은 원래 SHA-256 메시지 형태로 확인합니다. 서명 알고리즘에 따라 `approved-copy`의 공개키도 맞춰 제공합니다. 새 의견을 봉투로 저장하고 API 조회 때 권한 확인 후 복호화합니다. 의견 평문으로 저장된 이전 레코드는 원래 값을 조회합니다.
- `PqcWorkflowTest`: 정상 업무·권한·재시작·변조(F-01/T-01/P-01/T-02), 레거시 호환·GCM 문맥(L-01), 저장된 키 유실(K-02), 공개/개인키 쌍 불일치(K-02b), 레거시 DB의 첫 PQC 업그레이드(U-01)를 검사합니다.
- `README.md`: 알고리즘, 데이터 경로, 이전 데이터 호환, 키 손실 시 fail-closed 동작, 실습용 신원 및 외부 배포 한계를 안내합니다.

기존 DB 전량 재암호화나 재서명은 하지 않았습니다. 기존 RSA/EC 키와 레거시 DB만 있는 최초 업그레이드에서는 기존 키를 그대로 읽고, 누락된 PQC 키쌍만 생성한 뒤 DB에 도입 완료 마커를 씁니다. 이후 재시작에서는 해당 마커가 PQC 키 재생성을 차단합니다. 기존 PQC 봉투·의견·ML-DSA 승인이 있으면 마커가 없더라도 키 생성은 허용하지 않습니다. 개인키·로컬 DB는 프로젝트 ZIP에 넣지 않으며 빈 데이터 디렉터리에서 시작할 때 앱이 키와 DB를 생성합니다.

## 6. 전환 과정의 문제와 해결

초기 테스트에서 애플리케이션 부트스트랩 밖에서 테스트 서비스를 만들 때 SQLite 상위 데이터 폴더가 없고, BC Provider가 등록되지 않아 테스트 초기화가 실패했습니다. 제품 부트스트랩과 같은 조건을 테스트에 반영해 저장 디렉터리를 먼저 만들고 Bouncy Castle Provider를 등록한 뒤 성공 결과를 얻었습니다. 본 애플리케이션은 기존과 같이 `Application` 부트스트랩에서 BC Provider를 등록합니다.

이전 DB와 새 서명 알고리즘의 메시지 다이제스트가 달라 기존 승인 증거가 깨질 수 있어, 서명 방식별로 SHA-256(기존 ECDSA)과 SHA-512(새 ML-DSA) 지문 구성을 유지합니다. 오류·알고리즘 불일치를 무시하지 않고 승인본을 거부하도록 했습니다. HWP 공식 보고서 양식의 팀/성명 정보는 제공되지 않아 이 초안에서 임의로 채우지 않았습니다.

## 7. 기능 및 보안 검증

**시험 명령:** Java 21.0.13 Temurin, Maven 3.9.16, Windows 11, Bouncy Castle 1.86에서 `mvn -B test package` 실행. 최종 제출 ZIP을 `pqc-submission-extract-final-20261107-v3` 별도 임시 폴더에 추출해 같은 명령으로 재검증했습니다. 추출본 결과는 `BUILD SUCCESS`, `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`입니다.

| 시험 | 방법 | 관찰 결과 |
|---|---|---|
| 정상 업무·암호 적용 (F-01) | 새 계약 생성 → 전달 → 수신자 본문 열람 → 의견 → 승인 → 작성자 승인본 조회 | 통과. `contract-envelope-v2`, ML-KEM-768, ML-DSA-65 기록 확인. 평문 응답은 권한이 있는 API 요청에서만 복호화되어 제공 |
| 사용자별 접근 | 작성자·수신자 외 `dave`로 문서 읽기, 미전달 수신자 읽기; HTTP 요청은 `X-User: dave` 사용 | 둘 다 서비스 계층에서 거부; HTTP smoke의 비권한 조회 응답은 404 |
| 승인 검증 | 승인본 조회 및 승인 이력 재조회 | 통과. 서명 알고리즘, 승인자, 서명 메시지 검증 경로를 확인 |
| 기존 형식 호환 | 기존 RSA-OAEP/AES-GCM v1 봉투 복호화, 기존 ECDSA-P256/SHA-256 서명 확인 | 통과. 새 ML-DSA 검증 경로에서는 ECDSA 서명이 거부됨 |
| 저장 지속성 | F-01에서 동일 DATA_DIR로 서비스 객체 재생성, U-01에서 Spring Boot 웹 컨텍스트를 종료한 뒤 같은 DATA_DIR로 다시 기동 | 통과. 저장된 문서·승인본은 키 재생성 없이 복호화·검증됨. 재기동 후 승인본 `signature_valid=true`, 본문 동일 |
| 사후 변조 | F-01 테스트에서 승인된 레코드의 암호문 마지막 바이트를 변경한 뒤 승인본 읽기 | 통과. 승인 서명/봉투 지문 검증에서 오류 반환, 정상 승인본으로 취급하지 않음 |
| 저장 키 상태 (K-02/K-02b) | 기존 저장 데이터가 있는 상태에서 ML-KEM 개인키 삭제 또는 공개키 쌍 교체 후 Spring Boot 애플리케이션 컨텍스트로 기동 | 두 경우 모두 컨텍스트 시작이 `InvalidKeyException` 원인으로 실패. 키 파일 자동 재생성 없음 |
| 최초 업그레이드·HTTP 업무 흐름 (U-01) | RSA/EC 키 파일과 기존 형식 DB만 만든 뒤 Spring Boot 웹 컨텍스트 기동; HTTP API로 신규 문서 작성·전달·열람·승인·승인본 조회; 컨텍스트 재기동 후 승인본 재조회 | 통과. 레거시 v1 본문 열람, 신규 ML-KEM 봉투와 ML-DSA 승인, `pqc_keys_initialized` 기록, 재기동 후 `signature_valid=true`와 본문 동일 확인 |
| 최종 JAR의 OS 프로세스 재시작 | 현재 소스에서 패키징한 `target/application.jar`를 별도 DATA_DIR·포트 18083으로 실제 실행; HTTP 문서 생성·전달·승인·승인본 조회 후 프로세스를 종료하고 동일 설정으로 재실행 | 통과. 재기동 후 health=`ok`, 승인 scheme=`ML-DSA-65`, `signature_valid=true`, 본문이 재기동 전과 동일 |
| 제출 ZIP 재현성 | 최종 ZIP을 `pqc-submission-extract-final-20261107-v3`에 추출하고 Java 21/Maven 3.9.16으로 `mvn -B test package` 실행 | 통과. 추출본 `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. 압축기는 26개 항목을 생성했으며 DB·개인키·`target`은 제외했습니다. |
| Docker 신규 환경 실행 | 이번 직접 확인에서 수행하지 않음 | Maven 패키지와 앱 HTTP smoke 및 서비스 계층 테스트까지 확인. Docker daemon을 사용할 수 없어 `docker compose up` 환경 검증은 미수행 |

통합 테스트는 실제 ML-KEM 캡슐화·역캡슐화, ML-DSA 서명·검증을 수행합니다. `PqcWorkflowTest`의 5개 메서드는 F-01 `pqcDocumentWorkflowPersistsAndRejectsUnauthorizedOrTamperedAccess`, L-01 `legacyRsaEnvelopeAndEcdsaApprovalRemainReadable`, U-01 `legacyOnlyDatabaseCanStartAndTransitionToPqc`, K-02 `missingPersistedKeyFailsClosed`, K-02b `mismatchedPersistedKeyPairFailsClosed`입니다. 로컬 재현 뒤 상세 로그는 `target/surefire-reports/com.partner.contracts.PqcWorkflowTest.txt`와 `.xml`에서 확인할 수 있습니다. 직접 확인하지 않은 항목은 성능·메모리 벤치마크, 다중 OS/Docker 신규 환경, 외부 클라이언트 또는 언어 간 상호운용, 공식 FIPS 모듈 검증입니다. Bouncy Castle 일반 JCA Provider는 FIPS 검증 모듈이라고 주장하지 않습니다. TLS 미적용, 외부 인증 미구현, 서버 복호화 구조 때문에 서버 침해·가상 사용자 헤더 위조를 방어하는 종단 간 문서 암호화는 아닙니다.

**검증 범위의 신뢰 한계:** `/approved-copy`에서 서버는 저장된 서명과 서명 메시지를 검증하고 `signature_valid` 결과 및 서버가 관리하는 공개키를 응답합니다. 응답의 `signed_payload`는 JSON 객체이며, 서명 입력은 애플리케이션의 Jackson 직렬화 규칙에 의존합니다. RFC 8785/JCS의 정규 JSON이나 외부 검증용 원시 서명 바이트 프로토콜을 구현한 것은 아닙니다. 공개키도 독립 신뢰기관·핀닝으로 인증되지 않으므로, 현재 결과는 해당 서비스가 가진 승인 기록의 내부 일관성을 확인한 것입니다. 제3자가 서버와 독립해 검증 가능한 증명 형식은 후속 설계 항목입니다.

**요구-구현-검증 연결:**

| 요구·주장 | 구현 근거 | 재현 시험 |
|---|---|---|
| 본문·의견을 수신자별 PQC 보호 | `DocumentProtection.protect/open`, `ContractService.comment/comments` | F-01, L-01 |
| 전달·열람·승인 권한 및 승인본 무결성 | `ContractService.deliver/read/approve/approvedCopy`, `ApiController` 경로 인가 | F-01 (비권한 404와 암호문 변경 거부) |
| 이전 RSA/ECDSA 데이터 읽기 호환 | `DocumentProtection.open` 및 기존 승인 검증 분기 | L-01 |
| 레거시 DB 최초 PQC 생성 및 그 뒤 키 손실 차단 | Spring `Application.contracts` → `Application.openService`, `Storage.pqcKeysInitialized/hasPqcArtifacts/markPqcKeysInitialized`, `CredentialStore.loadPqc` | U-01, K-02, K-02b |
| 데이터와 승인 키가 프로세스 재시작 후 유지 | `CredentialStore`, SQLite `Storage`; 동일 `DATA_DIR` | F-01 및 HTTP smoke 재시작 |

HTTP smoke는 사용자 요청의 업무 기능과 저장소 재시작을 추가 확인하지만, `X-User` 값 자체가 위조 불가능하다는 점을 증명하지 않습니다. 실제 신원 인증과 공개키 신뢰 배포는 별도 시스템이 필요합니다.

**암호 입력·파생의 재현 정보:**

| 대상 | 실제 구현 규칙 |
|---|---|
| KEM/KTS | `javax.crypto.KEM.getInstance("ML-KEM", "BC")`; Bouncy Castle `KTSParameterSpec.Builder("AES", 256).build()`. BC 1.86 빌더 기본값은 KDF3와 SHA-256, `otherInfo` 빈 바이트열이며 결과 KEK 길이는 256 bit입니다. 별도 KDF 호출이나 XOF 입력은 없습니다. |
| 본문 AAD | `Json.bytes(context)`의 UTF-8 바이트. 문서 context 필드는 `domain`, `contract_id`, `version`, `author`, `recipient`, `title`, `created_at`; Jackson `ORDER_MAP_ENTRIES_BY_KEYS`로 맵 키를 정렬한 기본 compact JSON입니다. |
| 의견 AAD | 같은 직렬화 설정에서 `domain=contract-comment-v1`, `id`, `contract_id`, `version`, `author`, `created_at`를 포함합니다. |
| DEK 포장 AAD | UTF-8 `contract-key-wrap-v2` + 0x00 + UTF-8 수신자 ID + 0x00 + 문서/의견 context 바이트. GCM nonce는 매 포장마다 무작위 12 byte, 태그 128 bit입니다. |
| 승인 서명 입력 | Jackson 정렬 compact JSON의 UTF-8 바이트. 필드는 `domain`, `context`, `envelope_sha512`(레거시는 `envelope_sha256`), `approval_id`, `approver`, `approved_at`, `signature_scheme`; 정렬된 직렬화 키 순서는 알파벳순입니다. JCS/RFC 8785나 외부 고정 바이트 포맷은 아닙니다. |
| 시험 증거 위치 | 테스트 ID와 메서드는 위 목록에 표시. `mvn -B test package` 실행 결과는 `target/surefire-reports/com.partner.contracts.PqcWorkflowTest.txt` 및 `.xml`에 생성됩니다. ZIP에는 빌드 로그/`target`을 싣지 않으며 제출자가 같은 명령으로 재생성합니다. |

**제출 소스 식별:** `contract-inbox-pqc-submission.zip`, SHA-256 `589775B33BFC8B0EB2D2F26E5C276BDC1C1FDEB6C98957557AD78E86C3518EF1` (26개 항목). 이 값은 최종 ZIP을 재현 빌드한 뒤 계산했습니다.

## AI 활용 여부

- 활용함: OpenAI Codex (GPT-6 기반, 세부 UI 모델 버전은 기록되지 않음).
- 주요 프롬프트 1: “기존 소스의 암호 흐름·키·데이터 경로를 분석하고 PQC 전환안을 구현해 줘.” 활용: ML-KEM/ML-DSA 설계 및 코드 수정. 채택/수정한 내용은 직접 소스와 테스트로 대조했습니다.
- 주요 프롬프트 2: “요구된 흐름과 변조·권한·재시작 조건을 재현 가능한 검증으로 확인해 줘.” 활용: 통합 테스트 초안 작성. 실패를 수정하고 최종 실행 결과를 반영했습니다.
- 주요 프롬프트 3: “공식 보고서 양식에 맞춰 인벤토리, 우선순위, 가정, 대안, 한계를 기술해 줘.” 활용: 보고서 초안. 소스·설정 및 시험 결과와 대조해 미검증 사항을 분리했습니다.
- 답변 판단: AI 제안은 구현·시험을 거친 범위에서만 완료된 사실로 기재했습니다. NIST FIPS와 Bouncy Castle 공식 자료를 설계 근거로 참조했습니다.

## 참고자료

1. NIST, [FIPS 203: Module-Lattice-Based Key-Encapsulation Mechanism Standard](https://csrc.nist.gov/pubs/fips/203/final), 2024-08-13. 정오표/수정 공지 포함.
2. NIST, [FIPS 204: Module-Lattice-Based Digital Signature Standard](https://csrc.nist.gov/pubs/fips/204/final), 2024-08-13.
3. NIST, [SP 800-227: Recommendations for Key-Encapsulation Mechanisms](https://csrc.nist.gov/pubs/sp/800/227/final).
4. Bouncy Castle, [Java 1.86 release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/), [PQC Almanac — Java examples](https://downloads.bouncycastle.org/java/docs/PQC-Almanac.pdf).
5. Oracle, [Java SE 21 KEM API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/KEM.html).
