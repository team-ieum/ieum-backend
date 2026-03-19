---
name: verify-security
description: Spring Security 설정 및 JWT 인증 레이어 규칙 준수 여부를 검증합니다. SecurityConfig 설정, JWT 예외 처리, CustomUserDetails 구조, 세션 정책을 확인합니다.
---

# verify-security

## 목적

Spring Security 6.x 기반 인증 레이어가 프로젝트 규칙에 맞게 구현되었는지 검증합니다.

## 실행 시점

- SecurityConfig, JwtTokenProvider, JWT 필터를 수정했을 때
- 새로운 보안 설정이나 인증 컴포넌트를 추가했을 때
- PR 전 최종 검증 시

## Related Files

| File | Purpose |
|------|---------|
| `auth/src/main/java/com/ieum/auth/config/SecurityConfig.java` | Spring Security 설정 |
| `auth/src/main/java/com/ieum/auth/jwt/JwtTokenProvider.java` | JWT 토큰 생성·검증 |
| `auth/src/main/java/com/ieum/auth/jwt/JwtAuthenticationFilter.java` | JWT 인증 필터 |
| `auth/src/main/java/com/ieum/auth/jwt/JwtAuthenticationEntryPoint.java` | 401 응답 처리 |
| `auth/src/main/java/com/ieum/auth/security/CustomUserDetails.java` | UserDetails 구현체 |
| `auth/src/main/java/com/ieum/auth/security/CustomUserDetailsService.java` | UserDetailsService 구현체 |

## Workflow

### Check 1: SecurityConfig — STATELESS 세션 정책 확인

`SecurityConfig`는 반드시 `STATELESS` 세션 정책을 사용해야 합니다.

```bash
grep -rn "STATELESS" --include="*.java" . | grep -v test | grep -v build
```

**PASS:** `SessionCreationPolicy.STATELESS` 1건 이상
**FAIL:** 결과 없음 — `session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)` 추가 필요

### Check 2: SecurityConfig — CSRF 비활성화 확인

REST API이므로 CSRF를 비활성화해야 합니다.

```bash
grep -rn "csrf" --include="*.java" . | grep -v test | grep -v build | grep -i "disable"
```

**PASS:** `csrf.disable()` 또는 `AbstractHttpConfigurer::disable` 1건 이상
**FAIL:** 결과 없음 — CSRF 비활성화 설정 필요

### Check 3: SecurityConfig — Spring Security 6.x 람다 DSL 사용 확인

메서드 체이닝 방식이 아닌 람다 DSL 방식을 사용해야 합니다.

```bash
# 람다 DSL 확인
grep -rn "csrf(csrf\|session(session\|authorizeHttpRequests(auth\|exceptionHandling(exception" \
  --include="SecurityConfig.java" . | grep -v test | grep -v build
```

**PASS:** 결과가 1건 이상 (람다 DSL 사용)
**FAIL:** 결과 없음 — 람다 DSL 방식으로 변경 필요

### Check 4: JwtTokenProvider — @PostConstruct SecretKey 초기화 확인

SecretKey는 매번 생성하지 않고 `@PostConstruct`에서 초기화하여 재사용해야 합니다.

```bash
grep -rn "@PostConstruct" --include="JwtTokenProvider.java" . | grep -v test | grep -v build
grep -rn "SecretKey" --include="JwtTokenProvider.java" . | grep -v test | grep -v build
```

**PASS:** `@PostConstruct`와 `SecretKey` 필드 모두 존재
**FAIL:** 미존재 — `@PostConstruct` 초기화 및 `SecretKey` 필드 캐싱 필요

### Check 5: JWT 예외 처리 — CustomException 변환 확인

`JwtTokenProvider`에서 JWT 예외는 반드시 `CustomException`으로 변환해야 합니다.

```bash
grep -rn "ExpiredJwtException\|JwtException" --include="JwtTokenProvider.java" . | grep -v test | grep -v build
grep -rn "CustomException" --include="JwtTokenProvider.java" . | grep -v test | grep -v build
```

**PASS:** `ExpiredJwtException` → `TOKEN_EXPIRED`, `JwtException` → `TOKEN_INVALID` 변환 존재
**FAIL:** `CustomException` 없음 — JWT 예외를 `CustomException`으로 변환 필요

### Check 6: JwtAuthenticationEntryPoint — ApiResponse 반환 확인

인증 실패 시 `ApiResponse.error(ErrorCode.UNAUTHORIZED)` 형식의 JSON을 반환해야 합니다.

```bash
grep -rn "ApiResponse\|UNAUTHORIZED" --include="JwtAuthenticationEntryPoint.java" . | grep -v test | grep -v build
```

**PASS:** `ApiResponse`와 `UNAUTHORIZED` 모두 존재
**FAIL:** 미존재 — `ApiResponse.error(ErrorCode.UNAUTHORIZED)` 직렬화 응답 필요

### Check 7: JwtAuthenticationFilter — SecurityContext 오염 방지 확인

필터에서 JWT 검증 실패 시 `SecurityContextHolder.clearContext()`를 호출해야 합니다.

```bash
grep -rn "clearContext\|SecurityContextHolder" --include="JwtAuthenticationFilter.java" . | grep -v test | grep -v build
```

**PASS:** `clearContext()` 호출 존재
**FAIL:** 미존재 — 예외 발생 시 `SecurityContextHolder.clearContext()` 추가 필요

### Check 8: SecurityConfig — JwtAuthenticationFilter 등록 확인

`JwtAuthenticationFilter`가 `UsernamePasswordAuthenticationFilter` 앞에 등록되어야 합니다.

```bash
grep -rn "addFilterBefore\|JwtAuthenticationFilter" --include="SecurityConfig.java" . | grep -v test | grep -v build
```

**PASS:** `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)` 존재
**FAIL:** 미존재 — `addFilterBefore` 설정 추가 필요

## 예외사항

다음은 **위반이 아닙니다**:

1. **테스트 코드** — `src/test` 하위 파일은 검사 제외
2. **빌드 생성 파일** — `build/` 하위 파일은 검사 제외
3. **OAuth2 설정 누락** — OAuth2 로그인 설정은 별도 커밋에서 추가 (현재 미포함이 정상)
4. **formLogin/httpBasic 비활성화** — REST API이므로 비활성화가 올바른 설정
