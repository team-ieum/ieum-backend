# Skill: JPA 엔티티 설계

## 목적
IEUM 프로젝트의 PostgreSQL 기반 JPA 엔티티를 컨벤션에 맞게 생성한다.

## 주요 타입 규칙
- **PK**: UUID (`@GeneratedValue(strategy = GenerationType.UUID)`)
- **JSON 데이터**: JSONB (`@Column(columnDefinition = "jsonb")`)
- **시각**: TIMESTAMP (`LocalDateTime`)
- **암호화 필드**: TEXT (AES-256 암호화 후 저장)

## 기본 엔티티 템플릿

```java
@Entity
@Table(name = "<테이블명>")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class <Domain> extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    // Enum 매핑
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private <Status>Enum status;
}
```

## BaseEntity (공통 audit 필드)
`common` 모듈에 정의:
```java
// com.ieum.common.entity
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseEntity {
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

## IEUM 프로젝트 테이블별 엔티티 가이드

### users
```java
@Entity
@Table(name = "users")
public class User extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    private String passwordHash;  // 소셜 로그인이면 NULL

    @Column(nullable = false)
    private String provider;  // 'local', 'google'
}
```

### connected_accounts (OAuth 토큰 — AES-256 암호화)
```java
@Entity
@Table(name = "connected_accounts")
public class ConnectedAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String provider;  // 'google', 'notion'

    @Column(nullable = false, columnDefinition = "TEXT")
    private String accessToken;  // ⚠️ AES-256 암호화 저장

    @Column(columnDefinition = "TEXT")
    private String refreshToken;  // ⚠️ AES-256 암호화 저장

    private LocalDateTime expiresAt;
}
```

### workflow_versions (JSONB 필드)
```java
@Entity
@Table(name = "workflow_versions")
public class WorkflowVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String nodes;  // JSONB — 노드 목록 및 설정

    @Column(nullable = false, columnDefinition = "jsonb")
    private String edges;  // JSONB — 노드 간 연결 정보
}
```

## 연관관계 패턴

### 일대다 (1:N)
```java
// 부모 엔티티 — 필요한 경우에만 양방향
@OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Child> children = new ArrayList<>();

// 자식 엔티티 — FK 보유
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "parent_id")
private Parent parent;
```

### 다대다 (N:M) → 중간 테이블 엔티티로 분리
```java
@Entity
@Table(name = "a_b_mapping")
public class ABMapping {
    @ManyToOne(fetch = FetchType.LAZY) private A a;
    @ManyToOne(fetch = FetchType.LAZY) private B b;
}
```

## 체크리스트
- [ ] PK는 UUID 사용 (`GenerationType.UUID`)
- [ ] `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — JPA 프록시 필수
- [ ] 연관관계는 기본 `FetchType.LAZY`
- [ ] `@Column` nullable, length 명시
- [ ] Enum은 `EnumType.STRING` (ORDINAL 금지)
- [ ] `@EnableJpaAuditing` 선언 확인
- [ ] 암호화 필드(토큰, API Key)는 `columnDefinition = "TEXT"` 사용
- [ ] JSONB 필드는 `columnDefinition = "jsonb"` 사용
- [ ] `node_runs`의 input/output에 자격증명 포함 금지
