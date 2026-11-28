package com.majstr.backend.feature;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ObjectReadOnlyException;
import com.majstr.backend.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ObjectWriteGuardTest {

    @Mock ProjectRepository projectRepository;
    @InjectMocks ObjectWriteGuard guard;

    private final User free = User.builder().id(UUID.randomUUID()).plan(Plan.FREE).build();

    @Test
    void onAPlanWithNoObjectCapEveryObjectIsWritable_andNothingIsCounted() {
        User pro = User.builder().id(UUID.randomUUID()).plan(Plan.PRO).build();

        assertThat(guard.isWritable(object(pro))).isTrue();
        verifyNoInteractions(projectRepository);
    }

    @Test
    void onFreeTheFirstObjectsAreWritable_theNextIsNot() {
        Project firstMade = object(free);
        Project later = object(free);
        given(projectRepository.findCappedIdsOldestFirst(eq(free.getId()), any(Pageable.class)))
                .willReturn(List.of(firstMade.getId(), UUID.randomUUID()));

        assertThat(guard.isWritable(firstMade)).isTrue();
        assertThatThrownBy(() -> guard.requireWritable(later))
                .isInstanceOfSatisfying(ObjectReadOnlyException.class,
                        ex -> assertThat(ex.getWritableObjects()).isEqualTo(2));
    }

    @Test
    void theSampleAndAOneOffPaidObjectStayWritableOnFree_aTransferredOneDoesNot() {
        given(projectRepository.findCappedIdsOldestFirst(eq(free.getId()), any(Pageable.class))).willReturn(List.of());
        Project sample = object(free);
        sample.setSample(true);
        Project paid = object(free);
        paid.setPaidAt(Instant.now());
        Project transferred = object(free);
        transferred.setTransferredAt(Instant.now());

        assertThatCode(() -> guard.requireWritable(sample)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireWritable(paid)).doesNotThrowAnyException();
        assertThat(guard.isWritable(transferred)).isFalse();
    }

    private static Project object(User owner) {
        return Project.builder().id(UUID.randomUUID()).owner(owner).name("Обʼєкт").build();
    }
}
