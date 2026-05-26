package com.majstr.backend.storage;

public record StoredObject(
        String key,
        long size,
        String contentType
) {}
