package com.majstr.backend.dto;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.ClientType;

import java.time.Instant;
import java.util.UUID;

public record ClientResponse(
        UUID id,
        String fullName,
        String phone,
        String address,
        String email,
        // Document requisites (acts iteration).
        ClientType clientType,
        String taxId,
        String legalName,
        String legalAddress,
        String signatoryTitle,
        String signatoryName,
        Instant createdAt
) {
    public static ClientResponse from(Client client) {
        return new ClientResponse(
                client.getId(),
                client.getFullName(),
                client.getPhone(),
                client.getAddress(),
                client.getEmail(),
                client.getClientType(),
                client.getTaxId(),
                client.getLegalName(),
                client.getLegalAddress(),
                client.getSignatoryTitle(),
                client.getSignatoryName(),
                client.getCreatedAt()
        );
    }
}
