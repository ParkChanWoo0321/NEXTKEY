# 로컬 MySQL 회귀 테스트

Windows의 PowerShell, JDK 17, MySQL Server 8.0 바이너리가 필요합니다. 기존 MySQL 서비스의 비밀번호나 GCP 연결은 필요하지 않습니다.

프로젝트 루트에서 실행합니다.

```powershell
.\scripts\test-mysql.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

MySQL 설치 경로가 기본값과 다르면 `-MysqlBin '설치경로\bin'`을 추가합니다. Gradle 배포본과 의존성을 이미 내려받은 환경에서는 `-Offline`을 추가할 수 있습니다.

스크립트는 매번 새로운 임시 경로와 임의의 로컬 포트에 전용 MySQL을 시작합니다. 테스트 계정 비밀번호와 OTP는 실행마다 생성합니다. 호출 환경의 Spring DB·Flyway 연결 정보, 프로필, 시연 옵션과 JVM 옵션은 검증 프로세스 안에서 임시로 설정한 뒤 복원합니다. 기존 DB와 클라우드에는 연결하지 않습니다.

다음 항목이 모두 성공해야 정상 종료합니다.

- Flyway 마이그레이션과 전체 JUnit 테스트 실행. 실패·오류·건너뜀을 허용하지 않으며 필수 MySQL 통합 테스트의 실행 여부도 확인합니다.
- 실행 가능한 Spring Boot JAR 빌드와 개발 프로필의 기존 이용 기록 복구.
- 시연 계정이 있는 스키마를 운영 프로필로 실행할 때 기동 거부.
- 별도 빈 스키마의 운영 기동과 개발 계정 로그인·이전 Bearer 거부.

테스트 수는 결과 XML에서 집계하므로 고정 숫자로 성공을 판단하지 않습니다. 결과는 `build/reports/tests/test/index.html`과 `build/reports/mysql-verification.json`에서 확인합니다. JSON 요약은 모든 점검이 성공한 경우에 생성합니다.

종료 시 자신이 생성한 MySQL 프로세스와 검증된 임시 경로만 정리합니다. 테스트 실패는 PowerShell 오류로 전달합니다. 한글 PDF 테스트에 사용할 글꼴이 없으면 `NEXTKEY_PDF_FONT`에 로컬 한글 글꼴의 절대 경로를 지정합니다. Windows의 맑은 고딕이 있으면 자동으로 사용합니다.
