package com.majstr.backend.service;

import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ImageContentTypeDetector.ImageKind;
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
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final String LOGO_PREFIX = "logos";
    private static final int HEADER_PEEK_BYTES = 16;

    private final UserRepository userRepository;
    private final StorageService storage;

    @Transactional
    public String uploadLogo(UUID userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Logo file is empty");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // Read the upload once. Spring caps multipart at 2 MB, so an in-memory
        // copy is bounded. Earlier versions peeked the stream and tried to
        // re-open it — MultipartFile gives a fresh stream on every call, so
        // the file ended up with a duplicated 16-byte header on disk.
        byte[] content = file.getBytes();
        if (content.length < 4) {
            throw new UnsupportedMediaTypeException("Empty or truncated upload");
        }
        byte[] header = Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length));
        ImageKind kind = ImageContentTypeDetector.detect(header);

        StoredObject stored = storage.store(
                new ByteArrayInputStream(content),
                content.length,
                LOGO_PREFIX,
                kind.extension,
                kind.contentType
        );
        // Remove the old logo to avoid orphaning a file on disk.
        if (user.getLogoUrl() != null && !user.getLogoUrl().isBlank()) {
            tryDelete(user.getLogoUrl());
        }
        user.setLogoUrl(stored.key());
        return stored.key();
    }

    @Transactional
    public void deleteLogo(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getLogoUrl() == null || user.getLogoUrl().isBlank()) {
            return;
        }
        tryDelete(user.getLogoUrl());
        user.setLogoUrl(null);
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored object {}: {}", key, e.getMessage());
        }
    }
}
