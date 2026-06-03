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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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
}
