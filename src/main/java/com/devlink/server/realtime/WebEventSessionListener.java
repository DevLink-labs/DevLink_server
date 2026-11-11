package com.devlink.server.realtime;

import com.devlink.server.service.WebEventStreamService;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

public class WebEventSessionListener implements HttpSessionListener, HttpSessionIdListener {
    private final WebEventStreamService streams;

    public WebEventSessionListener(WebEventStreamService streams) {
        this.streams = streams;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        streams.closeSession(event.getSession().getId());
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        streams.closeSession(oldSessionId);
    }
}
