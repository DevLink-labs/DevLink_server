package com.devlink.server.repository;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.teamcontext.TeamPartContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamPartContextRepository extends JpaRepository<TeamPartContext, Long> {
    Optional<TeamPartContext> findFirstByTeamIdAndPartAAndPartBOrderByVersionDesc(
            Long teamId, AgentPart partA, AgentPart partB);

    Optional<TeamPartContext> findByTeamIdAndPartAAndPartBAndLastEventId(
            Long teamId, AgentPart partA, AgentPart partB, Long lastEventId);

    /** 한 파트가 참여하는 모든 쌍의 최신 버전. 쌍마다 한 행이다. */
    @Query("""
            select context
            from TeamPartContext context
            where context.teamId = :teamId
              and (context.partA = :part or context.partB = :part)
              and context.version = (
                  select max(latest.version)
                  from TeamPartContext latest
                  where latest.teamId = context.teamId
                    and latest.partA = context.partA
                    and latest.partB = context.partB)
            order by context.partA asc, context.partB asc
            """)
    List<TeamPartContext> findLatestByTeamIdAndPart(@Param("teamId") Long teamId, @Param("part") AgentPart part);
}
