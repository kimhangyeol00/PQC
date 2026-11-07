# 제출본 검증 안내

검증 환경: Windows, Temurin Java 21.0.13, Maven 3.9.16, Bouncy Castle 1.86.

## 실행

```sh
mvn -B test package
```

JUnit 테스트는 총 14개이며 실제 ML-KEM/ML-DSA 연산과 임시 SQLite DB·키를 사용합니다. 상세 결과는 `target/surefire-reports`에 생성됩니다. 정상적인 키 유실·불일치 거부 시험에서도 Spring 시작 실패 스택이 출력되므로 최종 JUnit 합계로 성공 여부를 판단합니다.

| 클래스·시험 | 확인 범위 |
|---|---|
| PqcWorkflowTest / 정상 흐름 | 작성·전달·열람·의견·승인·승인본, 비권한/미전달 거부, 재시작, 승인 봉투 변조 |
| PqcWorkflowTest / 레거시 암호 | RSA-OAEP/AES-GCM 및 ECDSA 읽기, GCM 문맥·암호문 변조 거부 |
| PqcWorkflowTest / 최초 업그레이드 | 기존 RSA/EC 키·v1 문서·실제 ECDSA 승인 보존, HTTP 신규 PQC 업무, 웹 컨텍스트 재기동 |
| PqcWorkflowTest / 키 유실·불일치 2개 | 실제 Spring 시작 실패, 기존 키 자동 대체 금지 |
| PqcHardeningTest / 의견 평문 교체 | 암호화 의견의 평문·알 수 없는 format·깨진 JSON 교체를 모두 409로 거부 |
| PqcHardeningTest / 기존 평문 | `{`로 시작하는 기존 일반 의견을 읽기 가능 |
| PqcHardeningTest / 승인 조합 | 수학적으로 유효한 ECDSA라도 PQC v2 문서에서는 거부 |
| PqcHardeningTest / JSON | 중복 필드·뒤따르는 추가 JSON 값 거부 |
| PqcHardeningTest / DB 마이그레이션 | 옛 6열 comments의 평문/v2 혼재 보존, 반복 실행, 응답 필드 유지, 재시작 후 변조 거부 |
| PqcHardeningTest / 의견 변조 | 의견 GCM 암호문 및 미지원 저장 형식 거부 |
| PqcHardeningTest / KEM 변조 | KEM ciphertext·wrapped_key·wrap nonce 변경 후 복호화 실패 |
| PqcHardeningTest / 양쪽 키 유실 | 초기화 마커가 없어도 기존 PQC 자료가 있으면 두 키 파일의 재생성 금지 |

## 실제 프로세스 검증

패키징 후 PowerShell 7에서 실행합니다.

```powershell
pwsh -File scripts/Verify-Http.ps1
```

스크립트는 loopback의 임시 포트와 새 시험 저장소로 JAR를 실행하고, 생성·전달·의견·승인·승인본 및 권한 거부를 확인합니다. 프로세스를 종료한 후 같은 저장소로 다시 실행해 본문·의견·공개키 보존을 확인합니다. 마지막에는 자신이 시작한 프로세스만 종료합니다. 결과는 `target/http-verification/result.json`에 기록됩니다.

## ZIP 재현

```sh
java scripts/Package.java --output ../contract-inbox-pqc-submission.zip
```

ZIP을 새 폴더에 풀고 그 안의 `contract-inbox`에서 빌드 및 HTTP 스크립트를 실행합니다. `target`, 업무 DB, 개인키, 실제 `.env`는 제출하지 않습니다. Windows Git 체크아웃의 CRLF가 Linux 실행 스크립트에 들어가지 않도록 저장소 `.gitattributes`에서 LF를 지정합니다.

## 범위와 한계

- 기존 데이터의 전량 재암호화·재서명은 하지 않습니다. 기존 v1 문서는 ECDSA 승인 읽기 및 신규 ML-DSA 승인이 가능하고, 신규 v2 문서는 ML-DSA만 허용합니다.
- 기존 의견 형식은 최초 마이그레이션 때 내용으로 분류합니다. 이미 그 전에 훼손돼 형식 단서가 사라진 자료의 원래 상태는 복구할 수 없습니다. 이후에는 저장된 형식으로 조회하며 평문으로 자동 재분류하지 않습니다.
- DB 전체(형식 표지 포함)와 서버 키를 함께 조작하는 공격, 이력 롤백을 외부 신뢰기관으로 검증하는 설계는 아닙니다.
- `X-User`는 실습 신원이며 실제 인증이 아닙니다. 독립적인 공개키 신뢰 배포·외부 승인본 검증은 미구현입니다.
- Docker 실행, 성능·메모리 벤치마크, 다중 OS, 외부 언어 상호운용 및 FIPS 모듈 인증을 검증했다는 주장은 하지 않습니다.
