package com.ieum.api.provider.dto;

import com.ieum.api.provider.model.ProviderInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "프로바이더 목록 응답")
@Getter
@Builder
public class ProviderListResponse {

    @Schema(description = "지원 AI 프로바이더 목록")
    private List<ProviderInfo> providers;
}
