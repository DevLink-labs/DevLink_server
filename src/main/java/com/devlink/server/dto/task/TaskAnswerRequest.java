package com.devlink.server.dto.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TaskAnswerRequest(
        @NotBlank @Size(max = 64) String requestKey,
        @NotBlank String message,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String questionEventId) {
}
