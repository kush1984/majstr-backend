package com.majstr.backend.service;

import com.majstr.backend.dto.CashFlowResponse;

import java.util.UUID;

/**
 * Which rows of «Мої гроші» a read covers: all of them, one object's, or only the ones that belong
 * to no object. A filter on the READ only — adding a row never asks about an object.
 */
public record CashScope(UUID projectId, boolean offObject) {

    public static final CashScope ALL = new CashScope(null, false);
    public static final CashScope OFF_OBJECT = new CashScope(null, true);

    public static CashScope object(UUID projectId) {
        return new CashScope(projectId, false);
    }

    public boolean includes(CashFlowResponse.Entry entry) {
        if (projectId != null) {
            return projectId.equals(entry.projectId());
        }
        return !offObject || entry.projectId() == null;
    }
}
