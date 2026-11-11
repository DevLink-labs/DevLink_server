package com.devlink.server.dto.teamcontext;

import java.time.Instant;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.teamcontext.TeamPartContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 한 파트 쌍의 최신 요약. 저장된 버전이 없으면 version 0과 빈 배열이다. */
public record TeamPartContextView(AgentPart counterpart, long version, Long lastEventId, Instant updatedAt,
        String model, String promptVersion, JsonNode common, JsonNode changed, JsonNode issues) {

    public static TeamPartContextView empty(AgentPart counterpart) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        return new TeamPartContextView(counterpart, 0, null, null, null, null,
                factory.arrayNode(), factory.arrayNode(), factory.arrayNode());
    }

    public static TeamPartContextView from(TeamPartContext context, AgentPart counterpart) {
        return new TeamPartContextView(counterpart, context.getVersion(), context.getLastEventId(),
                context.getCreatedAt(), context.getModel(), context.getPromptVersion(),
                context.getCommon(), context.getChanged(), context.getIssues());
    }
}
