package com.majstr.backend.dto;

/**
 * Where a client page posts an error report, and the environment to tag it with.
 *
 * @param endpoint    Sentry's envelope URL, key included
 * @param environment the backend's own Sentry environment, so portal reports sort with it
 */
public record MonitoringConfigResponse(String endpoint, String environment) {}
