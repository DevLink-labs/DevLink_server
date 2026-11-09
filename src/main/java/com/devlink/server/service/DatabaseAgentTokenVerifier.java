package com.devlink.server.service;

import java.util.Optional;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TeamMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Fallback
public class DatabaseAgentTokenVerifier implements AgentTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(DatabaseAgentTokenVerifier.class);

    private final AgentRepository agents;
    private final TeamMemberRepository members;

    public DatabaseAgentTokenVerifier(AgentRepository agents, TeamMemberRepository members) {
        this.agents = agents;
        this.members = members;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentPrincipal> verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256
                || rawToken.chars().anyMatch(Character::isWhitespace)) {
            log.debug("Agent token verification rejected: reason=invalid_token_format");
            return Optional.empty();
        }
        var agent = agents.findByConnectionTokenHash(AgentTokenHash.sha256(rawToken)).orElse(null);
        if (agent == null) {
            log.debug("Agent token verification rejected: reason=token_not_found");
            return Optional.empty();
        }
        if (!members.existsByTeamIdAndUserId(agent.getTeamId(), agent.getOwnerUserId())) {
            log.debug("Agent token verification rejected: reason=owner_not_in_team, agentId={}, teamId={}",
                    agent.getId(), agent.getTeamId());
            return Optional.empty();
        }
        return Optional.of(new AgentPrincipal(agent.getId(), agent.getOwnerUserId(), agent.getTeamId()));
    }
}
