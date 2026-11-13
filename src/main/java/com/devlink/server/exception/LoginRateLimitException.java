package com.devlink.server.exception;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;

public final class LoginRateLimitException extends BusinessException {
    private final long retryAfterSeconds;

    public LoginRateLimitException(long retryAfterSeconds) {
        super(ErrorCode.LOGIN_RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
