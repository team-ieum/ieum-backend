package com.ieum.api.webhookcredential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 실행 실패 알림 대상 지정/해제 요청.
 *
 * <p>지정과 해제를 같은 엔드포인트로 처리한다 — 한 번 켠 알림을 끌 수 없는 구멍을 막는다.
 */
public record UpdateAlertTargetRequest(
        @Schema(description = "true면 이 웹훅을 실패 알림 대상으로 지정, false면 해제", example = "true")
        @NotNull Boolean alertTarget
) {}
