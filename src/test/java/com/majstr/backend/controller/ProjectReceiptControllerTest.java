package com.majstr.backend.controller;

import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.dto.ProjectReceiptResponse;
import com.majstr.backend.dto.ProjectReceiptsResponse;
import com.majstr.backend.dto.ReceiptRecognizeResponse;
import com.majstr.backend.entity.Role;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.exception.ProjectReceiptValidationException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectReceiptService;
import com.majstr.backend.service.QrScanRateLimiter;
import com.majstr.backend.service.ReceiptScanRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProjectReceiptControllerTest {

    @Mock private ProjectReceiptService receiptService;
    @Mock private ReceiptScanRateLimiter receiptScanRateLimiter;
    @Mock private QrScanRateLimiter qrScanRateLimiter;
    @InjectMocks private ProjectReceiptController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID projectId = UUID.randomUUID();
    private final UUID receiptId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(
            userId, "master@example.com", "hash", Role.USER);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(testMessageSource()))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MessageSource testMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void list_carriesBothTotalsAndTheUnpricedCount() throws Exception {
        given(receiptService.list(projectId, userId)).willReturn(new ProjectReceiptsResponse(
                List.of(new ProjectReceiptResponse(receiptId, "Епіцентр", new BigDecimal("483.50"),
                        LocalDate.of(2026, 9, 8), true, true, false, null, null, null, 0)),
                new BigDecimal("483.50"), BigDecimal.ZERO, 0));

        mockMvc.perform(get("/api/projects/{id}/receipts", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].label").value("Епіцентр"))
                .andExpect(jsonPath("$.items[0].reimbursable").value(true))
                .andExpect(jsonPath("$.reimbursableTotal").value(483.50))
                .andExpect(jsonPath("$.unpricedCount").value(0));
    }

    /** The till upload: a photo and nothing else, under the client's own id. */
    @Test
    void add_passesTheEntityUuidThroughAndAcceptsAPhotoAlone() throws Exception {
        UUID entityId = UUID.randomUUID();
        given(receiptService.add(eq(projectId), eq(userId), eq(entityId), any(), isNull(), isNull(), isNull()))
                .willReturn(new ProjectReceiptResponse(entityId, "Чек №1", BigDecimal.ZERO, null,
                        true, true, false, null, null, null, 0));

        mockMvc.perform(multipart("/api/projects/{id}/receipts", projectId)
                        .file(new MockMultipartFile("file", "r.jpg", "image/jpeg", new byte[]{1, 2, 3, 4}))
                        .header("X-Entity-Uuid", entityId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Чек №1"))
                .andExpect(jsonPath("$.reimbursable").value(true));
    }

    @Test
    void add_missingPhotoIsATypedBadRequest() throws Exception {
        willThrow(new ProjectReceiptValidationException(
                "error.project-receipt.photo-required", "PROJECT_RECEIPT_PHOTO_REQUIRED"))
                .given(receiptService).add(any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(multipart("/api/projects/{id}/receipts", projectId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROJECT_RECEIPT_PHOTO_REQUIRED"));
    }

    @Test
    void update_flipsTheReceiptToTheMastersOwnCost() throws Exception {
        given(receiptService.update(eq(projectId), eq(receiptId), eq(userId), any()))
                .willReturn(new ProjectReceiptResponse(receiptId, "Епіцентр", new BigDecimal("483.50"),
                        LocalDate.of(2026, 9, 8), true, false, true, null, null, null, 0));

        mockMvc.perform(patch("/api/projects/{id}/receipts/{receiptId}", projectId, receiptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProjectReceiptRequest(
                                "Епіцентр", new BigDecimal("483.50"), LocalDate.of(2026, 9, 8),
                                false, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reimbursable").value(false));
    }

    /**
     * The QR read has its OWN bucket: it costs no model call and fires on every photo of a batch, so
     * sharing the recognition budget would let one shopping trip eat the pass that actually costs.
     */
    @Test
    void qr_spendsTheQrBucketAndNotTheRecognitionOne() throws Exception {
        given(qrScanRateLimiter.tryConsume(userId))
                .willReturn(new ReceiptScanRateLimiter.ConsumeResult(true, 0));
        given(receiptService.readQr(eq(projectId), eq(userId), any()))
                .willReturn(new ReceiptRecognizeResponse(true, "Епіцентр", new BigDecimal("483.50"),
                        LocalDate.of(2026, 9, 8), "4000123456", "77"));

        mockMvc.perform(post("/api/projects/{id}/receipts/qr", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"https://cabinet.tax.gov.ua/cashregs/check?fn=4000123456&id=77\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recognized").value(true))
                .andExpect(jsonPath("$.fiscalFn").value("4000123456"));

        verify(receiptScanRateLimiter, never()).tryConsume(any());
    }

    @Test
    void qr_overTheLimitIs429AndNeverReachesTheService() throws Exception {
        given(qrScanRateLimiter.tryConsume(userId))
                .willReturn(new ReceiptScanRateLimiter.ConsumeResult(false, 42));

        mockMvc.perform(post("/api/projects/{id}/receipts/qr", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"x\"}"))
                .andExpect(status().isTooManyRequests());

        verify(receiptService, never()).readQr(any(), any(), any());
    }

    @Test
    void recognize_spendsTheRecognitionBucket() throws Exception {
        given(receiptScanRateLimiter.tryConsume(userId))
                .willReturn(new ReceiptScanRateLimiter.ConsumeResult(true, 0));
        given(receiptService.recognize(projectId, receiptId, userId))
                .willReturn(ReceiptRecognizeResponse.read("Епіцентр", new BigDecimal("483.50"),
                        LocalDate.of(2026, 9, 8)));

        mockMvc.perform(post("/api/projects/{id}/receipts/{receiptId}/recognize", projectId, receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(483.50))
                // Only the QR knows the paper's identity; a vision read never invents one.
                .andExpect(jsonPath("$.fiscalFn").doesNotExist());
    }
}
