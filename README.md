# PQC 계약 문서함 전환

협력사 계약 문서함의 장기 문서 보호와 승인 검증을 위해 ML-KEM-768 및 ML-DSA-65를 적용한 PQC 전환 구현입니다.

## 구성

- [`contract-inbox/`](contract-inbox/): 애플리케이션 소스와 실행 구성
- [`TECHNICAL_REPORT.md`](TECHNICAL_REPORT.md): 암호 자산 인벤토리, 전환 우선순위, 구현·검증·한계
- [`contract-inbox-pqc-submission.zip`](contract-inbox-pqc-submission.zip): 문제 플랫폼 제출용 프로젝트 ZIP

## 실행 및 검증

아래 명령은 저장소의 `contract-inbox` 폴더에서 실행합니다.

Docker Compose 실행:

```sh
docker compose up --build -d
```

Java 21 및 Maven 3.9 이상이 설치된 환경의 테스트·패키징:

```sh
mvn -B test package
```

최종 제출 ZIP에서 재현한 결과는 테스트 13개 통과, `BUILD SUCCESS`입니다. Docker 신규 환경 실행은 이번 검증에 포함하지 않았습니다.

## 암호·운영 요약

신규 문서와 의견은 AES-256-GCM으로 암호화합니다. 작성자·수신자별 ML-KEM-768 캡슐화와 KTS 파생 AES-256 KEK로 데이터 키를 보호하며, 승인은 ML-DSA-65로 서명합니다. 기존 RSA/ECDSA 데이터는 호환 읽기 경로를 유지합니다. 기존 RSA/EC 키만 있는 저장소는 최초 PQC 도입을 허용하고, 이후 DB 마커와 PQC 자산을 기준으로 키 유실·불일치 시 시작을 거부합니다.

`X-User`는 실습용 가상 신원으로 실제 인증이 아닙니다. 서버 내부 승인 검증은 독립적인 제3자 검증을 제공하지 않습니다. 자세한 근거와 한계는 기술보고서를 참고하세요.
