package com.devlink.server.dto.gitidentity;

import java.util.Locale;
import com.devlink.server.common.StrictStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GitIdentityRequest(
        @NotBlank @Email @Size(max = 255)
        @JsonDeserialize(using = StrictStringDeserializer.class) String gitEmail) {
    public GitIdentityRequest {
        if (gitEmail != null) {
            gitEmail = gitEmail.trim().toLowerCase(Locale.ROOT);
        }
    }
}
