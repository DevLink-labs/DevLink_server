package com.devlink.server;

import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.git.DependencyGraphData.*;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.DependencyGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class DependencyGraphIntegrationTest extends AbstractIntegrationTest {
    private static final String FRONT = "a".repeat(64);
    private static final String BACK = "b".repeat(64);
    private static final String SHA = "c".repeat(40);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private DependencyGraphService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void publishesMatchesRoutesAndClearsWorkingFiles() throws Exception {
        User owner = user("민지");
        User other = user("외부인");
        Team team = teams.save(Team.create("의존성 테스트", UUID.randomUUID().toString()));
        long teamId = team.getId();
        members.save(TeamMember.create(teamId, owner.getId(), MemberRole.OWNER));
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Agent agent = Agent.create(teamId, owner.getId(), "Agent", "", ToolType.CODEX, "프로젝트");
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        agent = agents.save(agent);
        var backend = service.register(teamId, owner.getId(),
                new RegisterSource(BACK, "서버", "develop", RepoKind.BACKEND, agent.getId(), null, false));
        var frontend = service.register(teamId, owner.getId(),
                new RegisterSource(FRONT, "프론트", "main", RepoKind.FRONTEND, agent.getId(), backend.repoId(), false));
        String path = "/api/v1/teams/" + teamId + "/git";
        String bearer = "Bearer " + token;

        mvc.perform(get(path + "/dependency-sources/" + FRONT).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.publisher").value(true));
        var endpoint = new Node("handler", NodeKind.ENDPOINT, "GET events",
                new Location("TaskController.java", 2), new HttpRoute("GET", "/api/tasks/{id}/events"), null);
        var page = new Node("page", NodeKind.PAGE, "WorkspacePage", new Location("src/Page.tsx", 1), null, null);
        var call = new Node("call", NodeKind.HTTP_CLIENT, "getEvents", new Location("src/api.ts", 3),
                new HttpRoute("GET", "/api/tasks/{taskId}/events"), null);
        var edge = new Edge("page", "call", Relation.CALLS);
        publish(path, bearer, new Upload(BACK, "develop", 0, SHA, 1, List.of(endpoint), List.of()));
        var upload = new Upload(FRONT, "main", 0, SHA, 1, List.of(page, call), List.of(edge));
        publish(path, bearer, upload);
        publish(path, bearer, upload);
        assertThat(jdbc.queryForObject("select count(*) from file_dependencies where repo_id = ?",
                Long.class, frontend.repoId())).isEqualTo(1L);

        mvc.perform(get(path + "/dependency-graph").session(session(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges[?(@.relationType == 'HTTP_CALL')].targetId")
                        .value(org.hamcrest.Matchers.contains(backend.repoId() + ":handler")));
        mvc.perform(get(path + "/dependency-graph").session(session(other))).andExpect(status().isForbidden());

        observe(path, bearer, FRONT, new WorkingObservation("feat/graph", List.of(
                new WorkingFileEntry("src/Page.tsx", WorkingFileChangeType.MODIFIED))));
        mvc.perform(get(path + "/dependency-graph/activity").session(session(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].displayName").value("민지"));
        observe(path, bearer, FRONT, new WorkingObservation("feat/graph", List.of()));
        mvc.perform(get(path + "/dependency-graph/activity").session(session(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());

        // 3차 계약: detached HEAD(branch null)와 그래프에 등록되지 않은 저장소도 첫 업로드에서 저장소를 만들며 받는다.
        String unregistered = "d".repeat(64);
        observe(path, bearer, unregistered, new WorkingObservation(null, List.of(
                new WorkingFileEntry("README.md", WorkingFileChangeType.UNTRACKED))));
        assertThat(jdbc.queryForObject("""
                select count(*) from working_files w join team_repositories r on r.id = w.repo_id
                where r.team_id = ? and r.origin_url_sha256 = ? and w.branch_name is null
                """, Long.class, teamId, unregistered)).isEqualTo(1L);
        mvc.perform(get(path + "/dependency-graph/activity").session(session(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    private void publish(String path, String bearer, Upload upload) throws Exception {
        mvc.perform(post(path + "/file-dependencies:batch").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(upload)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
    }

    private void observe(String path, String bearer, String repoKey, WorkingObservation request) throws Exception {
        mvc.perform(put(path + "/dependency-graph/working-files/" + repoKey).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk());
    }

    private User user(String name) {
        return users.save(User.create(UUID.randomUUID() + "@example.com", name, name, "password"));
    }

    private MockHttpSession session(User user) {
        var session = new MockHttpSession();
        var auth = new UsernamePasswordAuthenticationToken(new UserPrincipal(user.getId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));
        return session;
    }
}
