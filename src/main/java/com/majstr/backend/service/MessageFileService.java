package com.majstr.backend.service;

import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.ProjectMessageFile;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectMessageFileRepository;
import com.majstr.backend.service.AttachmentContentTypeDetector.AttachmentKind;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Attachments on a message: storing what a stranger uploaded, and handing it back to the master.
 *
 * <p>Everything here treats the upload as hostile, because it is — the form is open to whoever holds
 * the link. The type is sniffed from the bytes, the size is capped, the count is capped, and the name
 * is only ever text. What is stored is addressed by an opaque key the client never sees.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageFileService {

    /** Per file. Generous enough for a phone photo or a scanned invoice, and no more. */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** Per message. Someone with something to say has better ways than a sixth attachment. */
    static final int MAX_FILES_PER_MESSAGE = 5;
    private static final int HEADER_PEEK_BYTES = 16;
    private static final String PREFIX = "messages";

    private final ProjectMessageFileRepository fileRepository;
    private final StorageService storage;

    /** The bytes of one attachment plus what is needed to serve it. */
    public record MessageFileContent(byte[] bytes, String contentType, String downloadName) {}

    /**
     * Store the files that came with a message.
     *
     * <p>Called inside the message's own transaction: a file that cannot be stored must take the whole
     * submission down, so the sender is told it failed and can retry, rather than getting a cheerful
     * "надіслано" for a message whose invoice never arrived.</p>
     */
    @Transactional
    public List<ProjectMessageFile> attach(ProjectMessage message, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<MultipartFile> real = files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (real.isEmpty()) {
            return List.of();
        }
        if (real.size() > MAX_FILES_PER_MESSAGE) {
            throw new UnsupportedMediaTypeException("error.upload.too-many");
        }
        List<ProjectMessageFile> stored = new ArrayList<>(real.size());
        for (MultipartFile file : real) {
            stored.add(store(message, file));
        }
        // Keep the message's own list in step. A freshly built entity carries the plain ArrayList from
        // its builder, not a Hibernate collection, so rows saved here would otherwise be invisible to
        // anything reading message.getFiles() in the same call — silently "no attachments".
        if (message.getFiles() != null) {
            message.getFiles().addAll(stored);
        }
        return stored;
    }

    private ProjectMessageFile store(ProjectMessage message, MultipartFile file) {
        // Checked before reading the bytes into memory: the servlet's own multipart limit is the real
        // defence, this is the one that produces a message the sender can understand.
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        if (content.length > MAX_FILE_BYTES) {
            // A lying Content-Length gets caught here.
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
        byte[] header = Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length));
        AttachmentKind kind = AttachmentContentTypeDetector.detect(header);

        StoredObject stored;
        try {
            stored = storage.store(new ByteArrayInputStream(content), content.length,
                    PREFIX, kind.extension, kind.contentType);
        } catch (IOException e) {
            // Rolls the submission back on purpose — see attach().
            throw new IllegalStateException("Could not store a message attachment", e);
        }
        return fileRepository.save(ProjectMessageFile.builder()
                .message(message)
                .storageKey(stored.key())
                .originalName(safeName(file.getOriginalFilename(), kind))
                .contentType(kind.contentType)
                .sizeBytes(content.length)
                .build());
    }

    /**
     * Read one attachment for the master, and record that it was opened — that timestamp is what the
     * six-month retention sweep measures, so it is written on every open, not only the first.
     *
     * <p>The message id is part of the lookup: a file is only reachable through the message it belongs
     * to, and the caller has already proven it owns that object.</p>
     */
    @Transactional
    public MessageFileContent open(UUID messageId, UUID fileId) {
        ProjectMessageFile file = fileRepository.findByIdAndMessageId(fileId, messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message file not found: " + fileId));
        byte[] bytes;
        try (InputStream in = storage.open(file.getStorageKey())
                .orElseThrow(() -> new ResourceNotFoundException("Message file content is gone"))) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read a message attachment", e);
        }
        file.setLastOpenedAt(Instant.now());
        // Opening it calls off a pending deletion — that is the deal the warning offers, and the six
        // months start again from here.
        file.setDeletionWarnedAt(null);
        return new MessageFileContent(bytes, file.getContentType(), downloadName(file));
    }

    /**
     * Delete the stored bytes for a message's attachments. The rows go with the message through the
     * FK; this is the half the database cannot do.
     *
     * <p>Best effort per file: a key already missing from storage must not stop the master from
     * deleting the message.</p>
     */
    public void deleteStoredFiles(ProjectMessage message) {
        if (message.getFiles() == null) {
            return;
        }
        for (ProjectMessageFile file : message.getFiles()) {
            try {
                storage.delete(file.getStorageKey());
            } catch (IOException | RuntimeException e) {
                log.warn("Could not delete stored attachment {} of message {}: {}",
                        file.getStorageKey(), message.getId(), e.getMessage());
            }
        }
    }

    /**
     * The sender's filename, kept only as a label.
     *
     * <p>Stripped of any path so a name like {@code ../../etc/passwd} cannot be read as one, and
     * truncated to the column. Never used to build a storage key — that comes from the storage layer.
     * A missing name falls back to something honest rather than blank.</p>
     */
    private static String safeName(String original, AttachmentKind kind) {
        if (original == null || original.isBlank()) {
            return "attachment." + kind.extension;
        }
        String base = original.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\r\\n\\t]", " ").trim();
        if (base.isEmpty()) {
            return "attachment." + kind.extension;
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    /** A filename safe to put in a header: quotes and control characters would break it. */
    private static String downloadName(ProjectMessageFile file) {
        String name = file.getOriginalName();
        if (name == null || name.isBlank()) {
            return "attachment";
        }
        return name.replaceAll("[\"\\r\\n]", "_");
    }
}
