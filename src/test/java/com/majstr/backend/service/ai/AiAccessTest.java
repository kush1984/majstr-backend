package com.majstr.backend.service.ai;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiAccessTest {

    @Mock UserRepository userRepository;
    @InjectMocks AiAccess aiAccess;

    private final UUID id = UUID.randomUUID();

    private void account(Plan plan, boolean verified) {
        given(userRepository.findById(id)).willReturn(Optional.of(
                User.builder().id(id).plan(plan).emailVerified(verified).build()));
    }

    @Test
    void aFreeAccountNobodyConfirmedSpendsNoModelCall() {
        account(Plan.FREE, false);

        assertThatThrownBy(() -> aiAccess.requireAllowed(id)).isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    void aConfirmedFreeAccountPasses() {
        account(Plan.FREE, true);

        assertThatCode(() -> aiAccess.requireAllowed(id)).doesNotThrowAnyException();
    }

    @Test
    void aPaidPlanPasses() {
        account(Plan.PRO, false);

        assertThatCode(() -> aiAccess.requireAllowed(id)).doesNotThrowAnyException();
    }
}
