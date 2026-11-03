package com.devlink.server.dto.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskQuestionRequest(
        @NotBlank @Size(max = 64) String requestKey,
        @NotBlank String message) {
}
