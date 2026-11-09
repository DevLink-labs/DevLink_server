package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.git.DependencyGraphData.*;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.DependencyGraphRepository;
import com.devlink.server.repository.DependencyGraphRepository.FileEdge;
import com.devlink.server.repository.DependencyGraphRepository.Stored;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.repository.WorkingFileRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DependencyGraphService {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<Relation> FILE_RELATIONS = Set.of(Relation.IMPORT, Relation.RENDERS, Relation.CALLS);

    private final DependencyGraphRepository graphs;
    private final TeamAuthorizationService teams;
    private final AgentRepository agents;
    private final TeamMemberRepository members;
    private final UserRepository users;
    private final WorkingFileRepository working;

    @Transactional
    public RepositoryInfo register(long teamId, long userId, RegisterSource request) {
        teams.requireTeamOwner(userId, teamId);
        validate(request != null && key(request.repoKey()) && text(request.displayName(), 120)
                && branch(request.branchName()) && request.repoKind() != null && request.publisherAgentId() != null);
        var agent = agents.findById(request.publisherAgentId())
                .filter(candidate -> candidate.getTeamId().equals(teamId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        teams.requireTeamMember(agent.getOwnerUserId(), teamId);
        if (request.targetRepoId() != null) {
            validate(request.repoKind() == RepoKind.FRONTEND && graphs.listInfo(teamId).stream().anyMatch(
                    repo -> repo.repoId() == request.targetRepoId() && repo.repoKind() == RepoKind.BACKEND));
        }
        long repoId = graphs.lockRepository(teamId, request.repoKey());
        validate(!Objects.equals(repoId, request.targetRepoId()));
        var previous = graphs.find(teamId, request.repoKey(), true).map(Stored::info);
        if (request.repoKind() == RepoKind.FRONTEND) {
            validate(graphs.listInfo(teamId).stream().noneMatch(repo -> Objects.equals(repo.targetRepoId(), repoId)));
        }
        boolean clear = request.resetSnapshot() || previous.filter(repo -> !repo.branchName().equals(request.branchName())
                || repo.repoKind() != request.repoKind()).isPresent();
        boolean same = previous.filter(repo -> repo.displayName().equals(request.displayName())
                && repo.branchName().equals(request.branchName()) && repo.repoKind() == request.repoKind()
                && Objects.equals(repo.publisherAgentId(), request.publisherAgentId())
                && Objects.equals(repo.targetRepoId(), request.targetRepoId())).isPresent();
        if (!same || clear) graphs.register(repoId, request, clear);
        return info(teamId, request.repoKey());
    }

    @Transactional(readOnly = true)
    public SourceConfig source(long teamId, AgentPrincipal principal, String repoKey) {
        requireAgent(teamId, principal);
        validate(key(repoKey));
        Stored stored = find(teamId, repoKey, false);
        var repo = stored.info();
        return new SourceConfig(repo.branchName(), repo.repoKind(), publisher(repo, principal), repo.revision(),
                repo.analysisCommitSha(), stored.analysisVersion());
    }

    @Transactional
    public RepositoryInfo upload(long teamId, AgentPrincipal principal, Upload request) {
        requireAgent(teamId, principal);
        validate(request != null && key(request.repoKey()) && branch(request.branchName())
                && request.expectedRevision() >= 0 && request.analysisVersion() == 1
                && request.analysisCommitSha() != null && request.analysisCommitSha().matches("(?:[a-f0-9]{40}|[a-f0-9]{64})"));
        Stored stored = find(teamId, request.repoKey(), true);
        var repo = stored.info();
        if (!publisher(repo, principal)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        if (!repo.branchName().equals(request.branchName())) conflict();
        Map<String, Node> byId = validateSnapshot(request, repo.repoKind());
        Snapshot snapshot = new Snapshot(request.nodes().stream().sorted(Comparator.comparing(Node::id)).toList(),
                request.edges().stream().sorted(Comparator.comparing(Edge::sourceId).thenComparing(Edge::targetId)
                        .thenComparing(Edge::relationType)).toList());
        if (request.analysisCommitSha().equals(repo.analysisCommitSha())
                && Objects.equals(request.analysisVersion(), stored.analysisVersion())) {
            if (snapshot.equals(stored.snapshot())) return repo;
            conflict();
        }
        if (request.expectedRevision() != repo.revision()) conflict();
        var files = snapshot.edges().stream().filter(edge -> FILE_RELATIONS.contains(edge.relationType()))
                .map(edge -> new FileEdge(byId.get(edge.sourceId()).source().relativePath(),
                        byId.get(edge.targetId()).source().relativePath(), edge.relationType().name()))
                .filter(edge -> !edge.source().equals(edge.target())).distinct().toList();
        graphs.publish(repo.repoId(), request.analysisCommitSha(), request.analysisVersion(), Instant.now(), snapshot, files);
        return info(teamId, request.repoKey());
    }

    /** 3차 IDX-3-02 계약: 최대 2,000개 고유 경로, 한도를 넘으면 이전 관측 유지, 최초 유효 업로드에서 저장소 생성. */
    @Transactional
    public void observe(long teamId, AgentPrincipal principal, String repoKey, WorkingObservation request) {
        requireAgent(teamId, principal);
        validate(key(repoKey) && request != null && (request.branch() == null || branch(request.branch()))
                && request.files() != null && request.files().size() <= 2000);
        var paths = new HashSet<String>();
        for (WorkingFileEntry file : request.files()) {
            validate(file != null && path(file.relativePath()) && file.changeType() != null
                    && paths.add(file.relativePath()));
        }
        long repoId = graphs.ensureRepository(teamId, repoKey);
        graphs.observe(repoId, principal.ownerUserId(), request.branch(), request.files(), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<RepositoryInfo> repositories(long teamId, long userId) {
        teams.requireTeamMember(userId, teamId);
        return graphs.listInfo(teamId);
    }

    @Transactional(readOnly = true)
    public TeamGraph graph(long teamId, long userId) {
        teams.requireTeamMember(userId, teamId);
        List<Stored> stored = graphs.list(teamId);
        List<TeamNode> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Map<RouteKey, List<String>> endpoints = new HashMap<>();
        Map<Long, RepositoryInfo> infoById = new HashMap<>();
        for (Stored entry : stored) {
            var repo = entry.info();
            long repoId = repo.repoId();
            infoById.put(repoId, repo);
            if (entry.snapshot() == null) continue;
            for (Node node : entry.snapshot().nodes()) {
                String id = qualified(repoId, node.id());
                nodes.add(new TeamNode(id, repoId, node.kind(), node.label(), node.source(), node.httpRoute(), node.schemaName()));
                if (node.kind() == NodeKind.ENDPOINT && repo.repoKind() == RepoKind.BACKEND) {
                    endpoints.computeIfAbsent(routeKey(repoId, node.httpRoute()), key -> new ArrayList<>()).add(id);
                }
            }
            for (Edge edge : entry.snapshot().edges()) {
                edges.add(new Edge(qualified(repoId, edge.sourceId()), qualified(repoId, edge.targetId()), edge.relationType()));
            }
        }
        long unmatched = 0;
        for (TeamNode node : nodes) {
            if (node.kind() != NodeKind.HTTP_CLIENT) continue;
            Long target = infoById.get(node.repoId()).targetRepoId();
            List<String> matches = target == null || node.httpRoute() == null ? List.of()
                    : endpoints.getOrDefault(routeKey(target, node.httpRoute()), List.of());
            if (matches.size() == 1) edges.add(new Edge(node.id(), matches.getFirst(), Relation.HTTP_CALL));
            else unmatched++;
        }
        return new TeamGraph(stored.stream().map(Stored::info).toList(), nodes, edges, unmatched);
    }

    @Transactional(readOnly = true)
    public Activity activity(long teamId, long userId) {
        teams.requireTeamMember(userId, teamId);
        Instant now = Instant.now();
        var repoIds = graphs.listInfo(teamId).stream().map(RepositoryInfo::repoId).toList();
        if (repoIds.isEmpty()) return new Activity(now, List.of());
        var memberIds = members.findByTeamIdOrderByJoinedAtAscIdAsc(teamId).stream()
                .map(TeamMember::getUserId).collect(Collectors.toSet());
        var observations = working.findByIdRepoIdInAndExpiresAtAfter(repoIds, now).stream()
                .filter(item -> memberIds.contains(item.getUserId())).toList();
        var names = users.findAllById(observations.stream().map(item -> item.getUserId()).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getName));
        var files = observations.stream().flatMap(item -> item.getFiles().stream().map(file ->
                new ActivityItem(item.getRepoId(), file.relativePath(), item.getUserId(),
                        names.get(item.getUserId()), item.getBranchName(), file.changeType(),
                        item.getObservedAt(), item.getExpiresAt()))).toList();
        return new Activity(now, files);
    }

    private Map<String, Node> validateSnapshot(Upload request, RepoKind repoKind) {
        validate(request.nodes() != null && request.edges() != null
                && request.nodes().size() <= 5000 && request.edges().size() <= 10000);
        Map<String, Node> nodes = new HashMap<>();
        for (Node node : request.nodes()) {
            validate(node != null && node.id() != null && node.id().matches("[a-zA-Z0-9_-]{1,128}")
                    && text(node.label(), 600) && node.kind() != null && node.source() != null
                    && path(node.source().relativePath()) && node.source().line() > 0
                    && (node.schemaName() == null || text(node.schemaName(), 63))
                    && nodes.putIfAbsent(node.id(), node) == null);
            boolean endpoint = node.kind() == NodeKind.ENDPOINT;
            boolean call = node.kind() == NodeKind.HTTP_CLIENT;
            validate((!endpoint || repoKind == RepoKind.BACKEND && node.httpRoute() != null)
                    && (!call || repoKind == RepoKind.FRONTEND)
                    && (node.httpRoute() == null || (endpoint || call) && validRoute(node.httpRoute())));
        }
        var unique = new HashSet<Edge>();
        for (Edge edge : request.edges()) {
            validate(edge != null && edge.relationType() != null && edge.relationType() != Relation.HTTP_CALL
                    && nodes.containsKey(edge.sourceId()) && nodes.containsKey(edge.targetId())
                    && unique.add(edge));
        }
        return nodes;
    }

    private static boolean validRoute(HttpRoute route) {
        return route.method() != null && METHODS.contains(route.method())
                && text(route.pathTemplate(), 500) && route.pathTemplate().matches(
                        "/(?:[a-zA-Z0-9_.~-]+|\\{[a-zA-Z0-9_]+\\})(?:/(?:[a-zA-Z0-9_.~-]+|\\{[a-zA-Z0-9_]+\\}))*");
    }

    private record RouteKey(long repoId, String method, String path) {}
    private static RouteKey routeKey(long repoId, HttpRoute route) {
        return new RouteKey(repoId, route.method(), route.pathTemplate().replaceAll("\\{[^/{}]+\\}", "{}"));
    }

    private static String qualified(long repoId, String id) {
        return repoId + ":" + id;
    }

    private void requireAgent(long teamId, AgentPrincipal principal) {
        if (principal == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (principal.teamId() != teamId) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        teams.requireTeamMember(principal.ownerUserId(), teamId);
    }

    private boolean publisher(RepositoryInfo info, AgentPrincipal principal) {
        return Objects.equals(info.publisherAgentId(), principal.agentId());
    }

    private RepositoryInfo info(long teamId, String repoKey) {
        return graphs.findInfo(teamId, repoKey).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Stored find(long teamId, String repoKey, boolean lock) {
        return graphs.find(teamId, repoKey, lock).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static boolean key(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean branch(String value) {
        return text(value, 255) && !value.startsWith("-") && !value.contains("..")
                && value.matches("[\\p{L}\\p{N}._/-]+");
    }

    private static boolean path(String value) {
        if (!text(value, 500) || value.getBytes(StandardCharsets.UTF_8).length > 1000
                || value.indexOf('\\') >= 0 || value.matches("^[a-zA-Z]:.*")) return false;
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static void validate(boolean valid) {
        if (!valid) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private static void conflict() {
        throw new BusinessException(ErrorCode.DEPENDENCY_GRAPH_CONFLICT);
    }
}
