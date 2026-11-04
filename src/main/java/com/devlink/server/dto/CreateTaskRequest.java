package com.devlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank @Size(max = 64) String requestKey,
        @NotNull @Positive Long receiverAgentId,
        // 구버전 브로커 호환을 위해 선택이다. 길이·공백은 코드포인트 기준으로 서비스에서 검증한다.
        String taskSummary,
        @NotBlank String prompt) {

    public CreateTaskRequest(String requestKey, Long receiverAgentId, String prompt) {
        this(requestKey, receiverAgentId, null, prompt);
    }
}
