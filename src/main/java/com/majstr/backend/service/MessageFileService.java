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
    private final StorageCleanup cleanup;

    /** The bytes of one attachment plus what is needed to serve it. */
    public record MessageFileContent(byte[] bytes, String contentType, String downloadName) {}

    /** One attachment already in storage, waiting for its row. */
    public record StoredUpload(String key, String originalName, String contentType, long sizeBytes) {}

    private record Checked(MultipartFile file, byte[] content, AttachmentKind kind) {}

    /**
     * Store the files that came with a message, outside any transaction. Every file is checked before
     * the first one is stored, so a bad fifth attachment costs no uploads. A file that cannot be stored
     * must take the whole submission down, so the sender is told it failed and can retry; what was
     * stored before it is removed.
     */
    public List<StoredUpload> store(List<MultipartFile> files) {
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
        List<Checked> checked = real.stream().map(MessageFileService::check).toList();
        List<StoredUpload> stored = new ArrayList<>(checked.size());
        try {
            for (Checked file : checked) {
                stored.add(store(file));
            }
        } catch (RuntimeException e) {
            discard(stored);
            throw e;
        }
        return stored;
    }

    /** Remove uploads whose message never landed. Nothing can find these keys afterwards. */
    public void discard(List<StoredUpload> uploads) {
        cleanup.afterCommit(uploads.stream().map(StoredUpload::key).toList());
    }

    /** Give stored files their rows, inside the message's own transaction. */
    @Transactional
    public List<ProjectMessageFile> attach(ProjectMessage message, List<StoredUpload> uploads) {
        if (uploads.isEmpty()) {
            return List.of();
        }
        List<ProjectMessageFile> stored = new ArrayList<>(uploads.size());
        for (StoredUpload upload : uploads) {
            stored.add(fileRepository.save(ProjectMessageFile.builder()
                    .message(message)
                    .storageKey(upload.key())
                    .originalName(upload.originalName())
                    .contentType(upload.contentType())
                    .sizeBytes(upload.sizeBytes())
                    .build()));
        }
        // Keep the message's own list in step. A freshly built entity carries the plain ArrayList from
        // its builder, not a Hibernate collection, so rows saved here would otherwise be invisible to
        // anything reading message.getFiles() in the same call — silently "no attachments".
        if (message.getFiles() != null) {
            message.getFiles().addAll(stored);
        }
        return stored;
    }

    private static Checked check(MultipartFile file) {
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
        return new Checked(file, content, AttachmentContentTypeDetector.detect(header));
    }

    private StoredUpload store(Checked file) {
        AttachmentKind kind = file.kind();
        try {
            StoredObject stored = storage.store(new ByteArrayInputStream(file.content()), file.content().length,
                    PREFIX, kind.extension, kind.contentType);
            return new StoredUpload(stored.key(), safeName(file.file().getOriginalFilename(), kind),
                    kind.contentType, file.content().length);
        } catch (IOException e) {
            // Takes the submission down on purpose — see store(List).
            throw new IllegalStateException("Could not store a message attachment", e);
        }
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
     * Delete a message's attachments: the rows now, the stored bytes once the transaction commits.
     *
     * <p>The rows are deleted explicitly even though the FK cascades. Reading {@code getFiles()} makes
     * them managed, and a managed file still pointing at a removed message fails the flush
     * ({@code TransientPropertyValueException}) — every delete of a message with an attachment was a
     * 500. The bytes go after the commit (B-25), fail-soft per key.</p>
     */
    public void deleteFiles(ProjectMessage message) {
        if (message.getFiles() == null || message.getFiles().isEmpty()) {
            return;
        }
        List<ProjectMessageFile> files = List.copyOf(message.getFiles());
        cleanup.afterCommit(files.stream().map(ProjectMessageFile::getStorageKey).toList());
        fileRepository.deleteAll(files);
        message.getFiles().clear();
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
