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
}
