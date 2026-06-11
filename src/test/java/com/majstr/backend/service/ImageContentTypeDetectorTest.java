package com.majstr.backend.service;

import com.majstr.backend.service.ImageContentTypeDetector.ImageKind;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageContentTypeDetectorTest {

    @Test
    void detect_recognisesPngMagicHeader() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        assertThat(ImageContentTypeDetector.detect(png)).isEqualTo(ImageKind.PNG);
    }

    @Test
    void detect_recognisesJpegMagicHeader() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
        assertThat(ImageContentTypeDetector.detect(jpeg)).isEqualTo(ImageKind.JPEG);
    }

    @Test
    void detect_rejectsRenamedExecutable() {
        // A .png file extension but the bytes are clearly something else.
        byte[] exe = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03}; // PE header "MZ"
        assertThatThrownBy(() -> ImageContentTypeDetector.detect(exe))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                // The exception message is a bundle key, localized by the advice.
                .hasMessage("error.upload.type");
    }

    @Test
    void detect_rejectsTruncatedInput() {
        assertThatThrownBy(() -> ImageContentTypeDetector.detect(new byte[]{0x00}))
                .isInstanceOf(UnsupportedMediaTypeException.class);
    }
}
