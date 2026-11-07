# 파트너 계약 문서함

계약 초안을 전달하고 문서 버전별 검토 의견과 최종 승인을 관리하는 로컬 웹 서비스입니다.

## 실행

Docker Engine 또는 Docker Desktop과 Docker Compose가 필요합니다. 프로젝트 폴더에서 실행합니다.

```sh
docker compose up --build -d
```

- 서비스: http://localhost:8080
- 업무 API 문서: http://localhost:8080/docs
- OpenAPI: http://localhost:8080/openapi.json

최초 빌드는 이미지와 Maven 의존성을 내려받으므로 인터넷 연결이 필요합니다. 기본 구성은 Java 21, Spring Boot, Maven, SQLite를 사용하며 Bouncy Castle 라이브러리가 포함되어 있습니다. 의존성 버전은 `pom.xml`에서 확인할 수 있습니다.

포트와 실행 옵션은 `.env.example`을 `.env`로 복사하여 설정합니다.

```sh
cp .env.example .env
docker compose logs -f app
docker compose down
```

`APP_PORT`는 호스트 포트, `SEED_DEMO`는 빈 저장소의 예시 문서 생성 여부, `JAVA_TOOL_OPTIONS`는 JVM 실행 옵션입니다. 기본 데이터는 `contract_data` 볼륨에 저장되며 컨테이너를 다시 만들어도 유지됩니다. `docker compose down -v`는 해당 프로젝트의 저장 데이터를 삭제하므로 초기화할 때만 사용합니다. `SEED_DEMO`를 바꿔도 이미 초기화된 저장소의 문서를 추가하거나 삭제하지 않습니다.

## 업무 사용법

화면의 가상 사용자 메뉴로 담당자를 선택합니다. 이 프로젝트는 로컬 실습용 사용자 선택 방식을 사용합니다.

| ID | 이름 | 소속 | 역할 |
|---|---|---|---|
| alice | 김지은 | 노스웍스 | 작성자 |
| bob | 이도윤 | 파트너랩 | 검토자 |
| carol | 박서연 | 파트너랩 | 검토자 |
| dave | 정민수 | 노스웍스 | 작성자 |

작성자는 제목·본문·수신 검토자를 지정하여 계약을 만들고 해당 버전을 전달합니다. 지정된 검토자는 전달받은 버전을 열람하여 의견을 남기고 최신 버전을 승인할 수 있습니다. 수정은 새 버전으로 등록하며 이전 버전과 승인 이력은 보존됩니다. 작성자와 지정된 수신자는 승인본을 내려받을 수 있습니다.

API 요청에는 `X-User` 헤더로 사용자 ID를 전달합니다. API 문서의 **Authorize**에도 같은 값을 입력할 수 있습니다. 경로와 요청·응답 형식은 업무 API 문서를 참고합니다.

```sh
curl -H 'X-User: alice' http://localhost:8080/contracts
```

## 로컬 개발

Java 21과 Maven 3.9 이상이 설치되어 있으면 Docker 없이 실행할 수 있습니다.

```sh
mvn clean package
java -jar target/application.jar
```

기본 포트는 8000이며 `PORT`로 변경할 수 있습니다. `DATA_DIR`는 데이터 저장 경로이며 기본값은 현재 폴더의 `data`입니다.

```text
src/main/java/       애플리케이션
src/main/resources/ 설정·정적 화면
pom.xml             의존성·빌드 설정
compose.yaml        로컬 실행 구성
scripts/            실행·압축 도구
```

의존성은 `pom.xml`, 컨테이너 구성은 `Dockerfile`과 `compose.yaml`에서 관리합니다.

## 암호 보호와 키

새 문서 본문과 의견은 AES-256-GCM으로 암호화하고, 문서별 AES 키는 수신자와 작성자 각각의 ML-KEM-768 공개키로 캡슐화합니다. 승인 메시지는 버전 맥락과 봉투의 SHA-512 지문을 ML-DSA-65로 서명합니다. 승인본 응답에는 저장된 승인 서명을 재검증한 결과와 해당 서명 알고리즘의 공개키가 포함됩니다. ML-KEM 또는 ML-DSA 처리에 실패하면 문서 읽기·승인도 실패합니다.

키와 SQLite DB는 `DATA_DIR` 아래 생성되고 서비스 재시작 후 유지됩니다. 제출 ZIP에는 이 런타임 키와 DB가 포함되지 않습니다. 파일 권한은 POSIX 환경에서 개인키 0600, 키 디렉터리 0700으로 제한합니다. 기존 RSA-OAEP/AES-GCM 문서와 ECDSA 승인은 이전 키 파일이 보존된 저장소에서 읽기 전용 호환 처리를 합니다. 새 자료는 PQC 형식으로만 생성합니다.

기존 RSA/EC 키와 DB만 있는 저장소는 첫 업그레이드에서 기존 키를 유지하면서 PQC 키를 생성할 수 있습니다. 생성 후 DB `metadata`에 `pqc_keys_initialized=1`을 기록합니다. 이 표시가 있거나 이미 PQC 봉투·의견·승인이 있는 저장소에서 키 파일이 빠졌거나 ML-KEM·ML-DSA 공개키와 개인키가 서로 맞지 않으면 서비스 시작을 거부합니다. 기존 키를 새 키로 조용히 바꾸면 과거 문서를 복호화하거나 승인 서명을 검증할 수 없게 되기 때문입니다. 키와 DB는 함께 백업하고 접근을 제한하세요.

이 프로젝트의 사용자 선택과 `X-User` 헤더는 실습용 가상 신원입니다. 외부 서비스에 배포할 때는 신뢰된 인증·권한 확인, 키 저장소/HSM, HTTPS와 PQC 또는 하이브리드 TLS 종단을 별도로 구성해야 합니다. 컨테이너의 기본 포트 매핑은 로컬 호스트에 한정됩니다.

PQC 워크플로 통합 테스트는 `mvn test`로 실행합니다. Docker 경로는 기존 실행 안내대로 `docker compose up --build -d`를 사용합니다.

## 소스 압축

프로젝트 폴더에서 실행합니다.

```sh
java scripts/Package.java
```

Java가 로컬에 없으면 Docker로 실행할 수 있습니다.

```sh
docker run --rm -v "$PWD:/project" -w /project eclipse-temurin:21-jdk-jammy java scripts/Package.java
```

`dist/preliminary-submission.zip`이 생성됩니다. 출력 위치를 변경하려면 `--output 경로.zip`을 지정합니다. ZIP의 최상위 폴더는 `contract-inbox`입니다.

기본 압축 도구는 데이터 폴더, 빌드 산출물, 로컬 환경설정과 일반적인 키 파일을 제외합니다. 별도 위치에 저장한 실행 데이터나 접속정보가 있다면 제출 전에 직접 제외해 주세요. 로컬에서 생성한 개인키·업무 DB·캐시는 제출하지 않습니다. 추가한 파일이 필요한 경우 ZIP에 포함됐는지 확인하고, 별도 폴더에 풀어 실행해 보세요.

