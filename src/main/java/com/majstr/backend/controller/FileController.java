package com.majstr.backend.controller;

import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.storage.StorageService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Hidden
public class FileController {

    private final StorageService storage;

    /**
     * Serves any object stored via {@link StorageService}. The full path
     * after {@code /api/files/} is treated as the object key — for example
     * {@code GET /api/files/logos/abc.png} resolves the key {@code logos/abc.png}.
     *
     * <p>Body comes back as {@code byte[]} so Spring sets a real
     * {@code Content-Length}. Earlier we returned an {@code InputStreamResource}
     * — Spring calls {@code contentLength()} on it before writing, which reads
     * and closes the stream, and the body ended up empty.</p>
     */
    @GetMapping("/**")
    public ResponseEntity<byte[]> get(HttpServletRequest request) throws IOException {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String key = full.startsWith("/api/files/") ? full.substring("/api/files/".length()) : full;
        if (key.isBlank() || key.contains("..")) {
            throw new ResourceNotFoundException("File not found");
        }
        byte[] body;
        try (InputStream stream = storage.open(key)
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + key))) {
            body = stream.readAllBytes();
        }
        String contentType = storage.contentType(key).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(body);
    }
}
