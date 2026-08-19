package com.majstr.backend.service;

import com.majstr.backend.dto.PublicActView;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PublicActPortalServiceTest {

    @Mock ProjectShareLinkRepository linkRepository;
    @Mock WorkActRepository actRepository;
    @Mock WorkActItemRepository itemRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectMessageRepository messageRepository;
    @Mock WorkActPdfService pdfService;
    @Mock ActCumulativeCalculator cumulativeCalculator;
    @Mock ActAddendumCreator addendumCreator;
    @Mock ActSignedCopyService signedCopy;
    @Mock PushService pushService;
    @Mock org.springframework.context.MessageSource messages;
    @InjectMocks PublicActPortalService service;

    private static final String TOKEN = "tok-123";

    private WorkAct act(WorkActStatus status) {
        User owner = User.builder().id(UUID.randomUUID()).companyName("ФОП Іван").fullName("Іван").build();
        Client client = Client.builder().id(UUID.randomUUID()).fullName("Олена").build();
        Project project = Project.builder().id(UUID.randomUUID()).owner(owner).client(client)
                .name("Квартира").address("вул. Тестова 1").build();
        return WorkAct.builder().id(UUID.randomUUID()).userId(owner.getId()).project(project)
                .number("7").kind(WorkActKind.INTERIM).status(status)
                .issuedAt(LocalDate.now()).periodFrom(LocalDate.now()).periodTo(LocalDate.now())
                .build();
    }

    private WorkActItem item(WorkAct a) {
        return WorkActItem.builder().workAct(a).type(ItemType.WORK).name("Шпаклювання")
                .unit(Unit.M2).unitPrice(new BigDecimal("145.00")).quantity(new BigDecimal("10.000"))
                .lineTotal(new BigDecimal("1450.00")).cumulativeBefore(BigDecimal.ZERO).sortOrder(0).build();
    }

    private void stubToken(WorkAct a) {
        ProjectShareLink link = ProjectShareLink.builder()
                .token(TOKEN).kind(ShareLinkKind.ACT).workAct(a).revoked(false).build();
        given(linkRepository.findByTokenAndKind(TOKEN, ShareLinkKind.ACT)).willReturn(Optional.of(link));
        given(actRepository.findById(a.getId())).willReturn(Optional.of(a));
    }

    @Test
    void view_draftAct_is404_evenWithAValidToken() {
        WorkAct a = act(WorkActStatus.DRAFT);
        stubToken(a);

        assertThatThrownBy(() -> service.view(TOKEN)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void view_sentAct_returnsTheDocument() {
        WorkAct a = act(WorkActStatus.SENT);
        stubToken(a);
        given(itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(a.getId())).willReturn(List.of(item(a)));

        PublicActView view = service.view(TOKEN);

        assertThat(view.number()).isEqualTo("7");
        assertThat(view.status()).isEqualTo("SENT");
        assertThat(view.total()).isEqualByComparingTo("1450.00");
        assertThat(view.items()).singleElement()
                .satisfies(i -> assertThat(i.unit()).isEqualTo("м²")); // pre-formatted label
    }

    @Test
    void sign_setsSigned_computesDocHash_andPushesTheMaster() throws Exception {
        WorkAct a = act(WorkActStatus.SENT);
        stubToken(a);
        given(itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(a.getId())).willReturn(List.of(item(a)));
        given(signedCopy.computeDocHash(any(), any())).willReturn("a".repeat(64));
        given(messages.getMessage(anyString(), any(), any())).willReturn("підписано");

        PublicActView view = service.sign(TOKEN, new SignRequest("Олена", "+380671112233"), "1.2.3.4", "UA");

        assertThat(a.getStatus()).isEqualTo(WorkActStatus.SIGNED);
        assertThat(a.getSignerName()).isEqualTo("Олена");
        assertThat(a.getSignerIp()).isEqualTo("1.2.3.4");
        assertThat(a.getDocHash()).hasSize(64); // hex SHA-256
        assertThat(view.status()).isEqualTo("SIGNED");
        verify(pushService).sendToUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void sign_alreadySignedAct_is409() {
        WorkAct a = act(WorkActStatus.SIGNED);
        stubToken(a);

        assertThatThrownBy(() -> service.sign(TOKEN, new SignRequest("Олена", "+380671112233"), "1.2.3.4", "UA"))
                .isInstanceOf(WorkActSignedException.class);
    }

    @Test
    void sign_emptyAct_isRefused() {
        // Belt-and-braces (review fix): share and offline-sign already refuse an empty act, but the
        // owner can still empty a SENT act via PUT /items — that emptiness must never become SIGNED.
        WorkAct a = act(WorkActStatus.SENT);
        stubToken(a);
        given(itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(a.getId())).willReturn(List.of());

        assertThatThrownBy(() -> service.sign(TOKEN, new SignRequest("Олена", "+380671112233"), "1.2.3.4", "UA"))
                .isInstanceOf(WorkActValidationException.class);
        assertThat(a.getStatus()).isEqualTo(WorkActStatus.SENT);
    }
}
