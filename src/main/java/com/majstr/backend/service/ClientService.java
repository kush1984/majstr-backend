package com.majstr.backend.service;

import com.majstr.backend.dto.ClientRequest;
import com.majstr.backend.dto.ClientResponse;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;

    @Transactional
    public ClientResponse create(ClientRequest req, UUID ownerId) {
        User owner = userRepository.getReferenceById(ownerId);
        Client client = Client.builder()
                .owner(owner)
                .fullName(req.fullName().trim())
                .phone(req.phone().trim())
                .address(normalize(req.address()))
                .email(normalize(req.email()))
                .build();
        return ClientResponse.from(clientRepository.save(client));
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> listForOwner(UUID ownerId) {
        return clientRepository.findByOwnerIdOrderByFullNameAsc(ownerId).stream()
                .map(ClientResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientResponse get(UUID id, UUID ownerId) {
        return ClientResponse.from(loadOwned(id, ownerId));
    }

    @Transactional
    public ClientResponse update(UUID id, ClientRequest req, UUID ownerId) {
        Client client = loadOwned(id, ownerId);
        client.setFullName(req.fullName().trim());
        client.setPhone(req.phone().trim());
        client.setAddress(normalize(req.address()));
        client.setEmail(normalize(req.email()));
        return ClientResponse.from(client);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        Client client = loadOwned(id, ownerId);
        clientRepository.delete(client);
    }

    /**
     * Loads a client by id and asserts the caller owns it. Throws 404 if the
     * client does not exist and 403 if it exists but belongs to someone else.
     */
    Client loadOwned(UUID id, UUID ownerId) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found: " + id));
        if (!client.getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Client does not belong to the current user");
        }
        return client;
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
