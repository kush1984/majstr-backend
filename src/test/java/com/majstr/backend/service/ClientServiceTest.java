package com.majstr.backend.service;

import com.majstr.backend.dto.ClientRequest;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import org.springframework.security.access.AccessDeniedException;

/**
 * Smoke-level guarantee that {@link ClientService#create} no longer
 * goes through any quota check. Even on a FREE plan, a contractor can
 * create as many clients as they want — the unit of paid value in
 * Majstr is the project, not the contact list.
 */
@ExtendWith(MockitoExtension.class)
class ClientServiceTest {

    @Mock UserRepository userRepository;
    @Mock ClientRepository clientRepository;
    @InjectMocks ClientService clientService;

    @Test
    void create_freePlanCanCreateManyClientsWithoutLimitError() {
        UUID ownerId = UUID.randomUUID();
        // Reference proxy stand-in; ClientService never actually loads it.
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(clientRepository.save(any(Client.class))).willAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> {
            for (int i = 0; i < 50; i++) {
                clientService.create(
                        new ClientRequest("Client #" + i, "+38050" + i, null, null),
                        ownerId);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void create_storesOptionalEmail() {
        UUID ownerId = UUID.randomUUID();
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(clientRepository.save(any(Client.class))).willAnswer(inv -> inv.getArgument(0));

        var resp = clientService.create(
                new ClientRequest("Олена", "+380671234567", "Київ", "olena@example.com"), ownerId);

        assertThat(resp.email()).isEqualTo("olena@example.com");
    }

    @Test
    void update_savesAllFields() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Client existing = Client.builder()
                .id(id)
                .owner(User.builder().id(ownerId).build())
                .fullName("Старе Ім'я").phone("+380000000000")
                .build();
        given(clientRepository.findById(id)).willReturn(Optional.of(existing));

        var resp = clientService.update(id,
                new ClientRequest("Нове Ім'я", "+380671112233", "Київ, вул. Хрещатик 1", "new@example.com"),
                ownerId);

        assertThat(existing.getFullName()).isEqualTo("Нове Ім'я");
        assertThat(existing.getPhone()).isEqualTo("+380671112233");
        assertThat(existing.getAddress()).isEqualTo("Київ, вул. Хрещатик 1");
        assertThat(existing.getEmail()).isEqualTo("new@example.com");
        assertThat(resp.fullName()).isEqualTo("Нове Ім'я");
        assertThat(resp.email()).isEqualTo("new@example.com");
    }

    @Test
    void update_foreignClient_throwsAccessDenied() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Client existing = Client.builder()
                .id(id)
                .owner(User.builder().id(ownerId).build())
                .fullName("X").phone("+1")
                .build();
        given(clientRepository.findById(id)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> clientService.update(id,
                new ClientRequest("Hacker", "+2", null, null), stranger))
                .isInstanceOf(AccessDeniedException.class);
        // Original data untouched.
        assertThat(existing.getFullName()).isEqualTo("X");
    }

    // ---- offline authoring: client-provided UUID makes create idempotent -----------------------

    @Test
    void create_withRequestedId_persistsThatId() {
        UUID ownerId = UUID.randomUUID();
        UUID requestedId = UUID.randomUUID();
        given(clientRepository.findById(requestedId)).willReturn(Optional.empty());
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(clientRepository.save(any(Client.class))).willAnswer(inv -> inv.getArgument(0));

        var resp = clientService.create(
                new ClientRequest("Офлайн", "+380670000000", null, null), ownerId, requestedId);

        assertThat(resp.id()).isEqualTo(requestedId);
    }

    @Test
    void create_withRequestedId_alreadyExistsAndOwned_isIdempotentNoInsert() {
        UUID ownerId = UUID.randomUUID();
        UUID requestedId = UUID.randomUUID();
        Client existing = Client.builder()
                .id(requestedId)
                .owner(User.builder().id(ownerId).build())
                .fullName("Вже є").phone("+380671111111")
                .build();
        given(clientRepository.findById(requestedId)).willReturn(Optional.of(existing));

        var resp = clientService.create(
                new ClientRequest("Дубль", "+380672222222", null, null), ownerId, requestedId);

        // Replay returns the existing client and never inserts a duplicate.
        assertThat(resp.id()).isEqualTo(requestedId);
        assertThat(resp.fullName()).isEqualTo("Вже є");
        org.mockito.Mockito.verify(clientRepository, org.mockito.Mockito.never()).save(any(Client.class));
    }

    @Test
    void create_withRequestedId_belongsToAnotherUser_throwsAccessDenied() {
        UUID ownerId = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID requestedId = UUID.randomUUID();
        Client existing = Client.builder()
                .id(requestedId)
                .owner(User.builder().id(stranger).build())
                .fullName("Чужий").phone("+1")
                .build();
        given(clientRepository.findById(requestedId)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> clientService.create(
                new ClientRequest("Викрадач", "+2", null, null), ownerId, requestedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        // Client deletes have been offline-replayable for a while, so this gap was already
        // live: a replayed delete whose first response was lost hit a 404, which the outbox
        // classifies as a permanent rejection and shows as "not saved to cloud".
        UUID ownerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        given(clientRepository.findById(id)).willReturn(Optional.empty());

        assertThatCode(() -> clientService.delete(id, ownerId)).doesNotThrowAnyException();

        org.mockito.Mockito.verify(clientRepository, org.mockito.Mockito.never()).delete(any(Client.class));
    }

    @Test
    void delete_somebodyElsesClientIsStill403() {
        // Idempotency must not slide into "anyone may delete anything that exists".
        UUID ownerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Client theirs = Client.builder().id(id)
                .owner(User.builder().id(UUID.randomUUID()).build())
                .fullName("Чужий").phone("+1").build();
        given(clientRepository.findById(id)).willReturn(Optional.of(theirs));

        assertThatThrownBy(() -> clientService.delete(id, ownerId))
                .isInstanceOf(AccessDeniedException.class);

        org.mockito.Mockito.verify(clientRepository, org.mockito.Mockito.never()).delete(any(Client.class));
    }
}
