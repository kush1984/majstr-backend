package com.majstr.backend.dto;

import com.majstr.backend.entity.Client;

import java.time.Instant;
import java.util.UUID;

public record ClientResponse(
        UUID id,
        String fullName,
        String phone,
        String address,
        Instant createdAt
) {
    public static ClientResponse from(Client client) {
        return new ClientResponse(
                client.getId(),
                client.getFullName(),
                client.getPhone(),
                client.getAddress(),
                client.getCreatedAt()
        );
    }
}
