package com.devlink.server.dto;

import java.util.Locale;
import com.devlink.server.validation.Utf8Password;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Utf8Password String password,
        boolean rememberMe) {
    public LoginRequest(String email, String password) {
        this(email, password, false);
    }

    public LoginRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }
}
