package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.realtime.WebEventEntityListener.MembershipChanged;
import com.devlink.server.realtime.WebEventEntityListener.TaskChanged;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class WebEventStreamServiceTest {
    private final WebEventQueryService queries = mock(WebEventQueryService.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private WebEventStreamService streams;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        streams = new WebEventStreamService(queries, Runnable::run, 1) {
            @Override SseEmitter createEmitter() { return emitter; }
        };
        session = new MockHttpSession();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(new UserPrincipal(1), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
    }

    @Test
    void requiresTheAuthenticatedUsersSession() {
        assertThatThrownBy(() -> streams.subscribe(null, session)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> streams.subscribe(new UserPrincipal(1), null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> streams.subscribe(new UserPrincipal(2), session)).isInstanceOf(BusinessException.class);
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThatThrownBy(() -> streams.subscribe(new UserPrincipal(1), session)).isInstanceOf(BusinessException.class);
    }

    @Test
    void sessionLogoutClosesStreamsAndPreventsFurtherQueries() {
        streams.subscribe(new UserPrincipal(1), session);
        streams.closeSession(session.getId());
        streams.taskChanged(new TaskChanged(10L, 11L));
        verify(emitter).complete();
        verify(queries, never()).task(1, 10L, 11L);
    }

    @Test
    void membershipChangeClosesExistingSubscription() {
        streams.subscribe(new UserPrincipal(1), session);
        streams.membershipChanged(new MembershipChanged(1L));
        streams.taskChanged(new TaskChanged(10L, 11L));
        verify(emitter).complete();
        verify(queries, never()).task(1, 10L, 11L);
    }

    @Test
    void limitsConnectionsPerUserAndNotifiesTheReplacedClient() throws Exception {
        streams.subscribe(new UserPrincipal(1), session);
        clearInvocations(emitter);
        streams.subscribe(new UserPrincipal(1), session);
        var frames = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(frames.capture());
        assertThat(frames.getAllValues().getFirst().build().stream()
                .anyMatch(part -> part.getData().toString().contains("event:subscription-replaced"))).isTrue();
        verify(emitter).complete();
    }

    @Test
    void heartbeatDropsInvalidatedSessions() {
        streams.subscribe(new UserPrincipal(1), session);
        session.invalidate();
        streams.heartbeat();
        verify(emitter).complete();
    }

    @Test
    void failedWritesRemoveTheSubscription() throws Exception {
        streams.subscribe(new UserPrincipal(1), session);
        clearInvocations(emitter);
        doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        streams.heartbeat();
        streams.taskChanged(new TaskChanged(10L, 11L));
        verify(emitter).complete();
        verify(queries, never()).task(1, 10L, 11L);
    }

    @Test
    void oneConnectionDrainsEventsInSubmissionOrder() {
        var scheduled = new ArrayList<Runnable>();
        var queued = new WebEventStreamService(queries, scheduled::add, 1) {
            @Override SseEmitter createEmitter() { return emitter; }
        };
        queued.subscribe(new UserPrincipal(1), session);
        queued.taskChanged(new TaskChanged(10L, 11L));
        queued.taskChanged(new TaskChanged(10L, 12L));
        assertThat(scheduled).hasSize(1);
        scheduled.getFirst().run();
        var order = inOrder(queries);
        order.verify(queries).task(1, 10L, 11L);
        order.verify(queries).task(1, 10L, 12L);
    }

    @Test
    void aSlowConnectionHasABoundedQueueAndClosesOnOverflow() {
        var scheduled = new ArrayList<Runnable>();
        var queued = new WebEventStreamService(queries, scheduled::add, 1) {
            @Override SseEmitter createEmitter() { return emitter; }
        };
        queued.subscribe(new UserPrincipal(1), session);
        for (long id = 1; id <= 65; id++) queued.taskChanged(new TaskChanged(10L, id));
        verify(emitter).complete();
        scheduled.getFirst().run();
        verify(queries, never()).task(1, 10L, 1L);
    }
}
