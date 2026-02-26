# Skill: 새 Gradle 서브모듈 추가

## 목적
IEUM 멀티모듈 프로젝트에 새 서브모듈을 일관된 방식으로 추가한다.

## 현재 모듈 구조
```
ieum-backend/
├── api/             # 메인 진입점, REST API (SpringBoot 실행 모듈)
├── auth/            # 인증/인가 (JWT + OAuth2)
├── workflow-core/   # 워크플로우 엔진
├── ai/              # AI 연동 (Claude, OpenAI 등)
├── integration/     # 외부 서비스 연동 (Google, Notion)
└── common/          # 공통 유틸, 예외, BaseEntity
```

### 모듈 간 의존 방향 (단방향 유지 필수)
```
api → workflow-core, auth
workflow-core → integration, ai
integration → auth
모든 모듈 → common
```

## 절차

### 1. 디렉토리 및 build.gradle 생성
```
<모듈명>/
  build.gradle
  src/main/java/com/ieum/<모듈명>/
  src/main/resources/
  src/test/java/com/ieum/<모듈명>/
```

### 2. build.gradle 템플릿
```gradle
// 실행 가능한 모듈인 경우만 추가 (현재 api 모듈만 해당):
// plugins { id 'org.springframework.boot' }

dependencies {
    implementation project(':common')
    // 필요한 의존성 추가
}
```

### 3. settings.gradle 등록
```gradle
rootProject.name = 'ieum-backend'

include(
        'api',
        'workflow-core',
        'integration',
        'ai',
        'auth',
        'common',
        '<새모듈명>'   // 추가
)
```

### 4. 다른 모듈에서 의존성 참조
```gradle
// 참조하는 모듈의 build.gradle
dependencies {
    implementation project(':<새모듈명>')
}
```

## 모듈 유형별 가이드
| 유형 | Spring Boot plugin | 설명 |
|------|-------------------|------|
| 실행 진입점 (api만 해당) | ✅ 필요 | bootJar 생성, main 클래스 보유 |
| 라이브러리 | ❌ 불필요 | plain jar, 다른 모듈에서 참조 |

## 주의사항
- 공통 의존성(Lombok, Test 등)은 루트 `build.gradle`의 `subprojects`에 이미 선언 → 중복 선언 불필요
- `compileOnly`, `annotationProcessor`는 각 모듈에서 상속됨
- `dependencyManagement`로 Spring Boot BOM 3.4.3이 루트에서 관리됨
- 순환 참조 금지: 새 모듈 추가 시 의존 방향 검토 필수
- 패키지 루트는 반드시 `com.ieum.<모듈명>` (scanBasePackages = "com.ieum")
