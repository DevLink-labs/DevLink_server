package com.devlink.server.dto;

import jakarta.validation.constraints.Size;

public record UpdateAgentRequest(
        @Size(max = 100) String name,
        @Size(max = 255) String roleDescription,
        @Size(max = 120) String projectName) { }
