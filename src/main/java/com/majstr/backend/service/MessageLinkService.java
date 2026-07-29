package com.majstr.backend.service;

import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.dto.MessageLinkInfo;
import com.majstr.backend.dto.MessageLinkState;
import com.majstr.backend.dto.MessageLinkRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The master's message link: one URL per object that opens a form and nothing else.
 *
 * <p>Why it is not the portal link. The portal shows the client their estimate — prices, totals,
 * deposit. A master who wants a photo from a supplier or a note from a colleague would, with one link,
 * have to hand over the client's quote to get it. So this is a second link of its own
 * {@link ShareLinkKind}, and every lookup names the kind it expects.</p>
 *
 * <p>Minted on first ask and reused after — one live URL per object, so a link already sent by Viber
 * keeps working instead of being quietly replaced the next time the master opens the screen.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLinkService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String MESSAGE_PATH = "/message/index.html?m=";

    private final ProjectShareLinkRepository linkRepository;
    private final ProjectMessageRepository messageRepository;
    private final ProjectService projectService;
    private final PortalProperties portalProperties;
    private final PushService pushService;
    private final MessageFileService messageFileService;

    /** The object's message URL, minted if it does not exist yet. Owner-only. */
    @Transactional
    public MessageLinkState state(UUID projectId, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        ProjectShareLink link = linkRepository
                .findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                        projectId, ShareLinkKind.MESSAGE)
                .filter(existing -> existing.isUsable(Instant.now()))
                .orElseGet(() -> linkRepository.save(ProjectShareLink.builder()
                        .project(project)
                        .token(generateToken())
                        .kind(ShareLinkKind.MESSAGE)
                        .build()));
        return new MessageLinkState(buildUrl(link.getToken()));
    }

    /**
     * Revoke the link. Anyone still holding the old URL gets a 404 — which is the point: it is how a
     * master stops a link they sent to the wrong person. A new one is minted on the next ask.
     */
    @Transactional
    public void revoke(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        linkRepository.findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
                        projectId, ShareLinkKind.MESSAGE)
                .ifPresent(link -> link.setRevoked(true));
    }

    /**
     * What the public form is allowed to know: whose object it is and what it is called, so whoever
     * opens the link can tell they are writing to the right person. No money, no client, no estimates.
     */
    @Transactional(readOnly = true)
    public MessageLinkInfo info(String token) {
        Project project = resolve(token).getProject();
        return new MessageLinkInfo(
                project.getName(),
                project.getOwner().getCompanyName() != null && !project.getOwner().getCompanyName().isBlank()
                        ? project.getOwner().getCompanyName()
                        : project.getOwner().getFullName());
    }

    /**
     * Store a message sent through the link, with whatever was attached. The caller has already applied
     * the rate limit.
     *
     * <p>One transaction covering both: an attachment that cannot be stored takes the message with it,
     * so the sender sees a failure and retries, instead of a cheerful "надіслано" for a message whose
     * invoice never arrived.</p>
     */
    @Transactional
    public QuestionResponse submit(String token, MessageLinkRequest req, List<MultipartFile> files,
                                   String senderIp) {
        Project project = resolve(token).getProject();
        ProjectMessage message = messageRepository.save(ProjectMessage.builder()
                .project(project)
                // No estimate: nobody was looking at one. This is what V74 made storable.
                .authorName(req.authorName().trim())
                .authorPhone(blankToNull(req.authorPhone()))
                .message(req.message().trim())
                .authorIp(senderIp)
                .build());
        int attached = messageFileService.attach(message, files).size();

        // Fail-soft, exactly as the portal's question is: a push that does not go out must not lose
        // the message that was already saved.
        try {
            // The clip count goes in the notification: a master who sees an invoice arrived may want to
            // open the app now rather than after the next coffee.
            String body = message.getAuthorName() + ": " + message.getMessage()
                    + (attached > 0 ? " 📎" + attached : "");
            pushService.sendToUser(project.getOwner(),
                    "Нове повідомлення · " + project.getName(),
                    body,
                    "/projects/" + project.getId());
        } catch (RuntimeException e) {
            log.warn("Push for message {} on project {} failed: {}",
                    message.getId(), project.getId(), e.getMessage());
        }
        return QuestionResponse.from(message);
    }

    /**
     * A usable MESSAGE link, or 404. Names the kind so a portal token cannot be posted to this form —
     * and, more importantly, so a message token can never be read as a portal one.
     */
    private ProjectShareLink resolve(String token) {
        return linkRepository.findByTokenAndKind(token, ShareLinkKind.MESSAGE)
                .filter(link -> link.isUsable(Instant.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Message link not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private String buildUrl(String token) {
        return portalProperties.publicBaseUrl() + MESSAGE_PATH + token;
    }
}
