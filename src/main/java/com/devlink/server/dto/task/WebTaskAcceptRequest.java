package com.devlink.server.dto.task;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record WebTaskAcceptRequest(@NotNull @PositiveOrZero Long version) {
}
