package com.ieum.api.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "사용자 정보 수정 요청")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {

    @Schema(description = "변경할 이름 (최대 100자)", example = "홍길동")
    @NotBlank
    @Size(max = 100)
    private String name;
}
