package com.majstr.backend.service;

import com.majstr.backend.storage.UnsupportedMediaTypeException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ImageBoundsTest {

    /** A PNG that is nothing but a signature and a header declaring these dimensions, with alpha. */
    private static byte[] pngDeclaring(int width, int height) throws IOException {
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(ihdr)) {
            data.write("IHDR".getBytes(StandardCharsets.US_ASCII));
            data.writeInt(width);
            data.writeInt(height);
            data.write(new byte[]{8, 6, 0, 0, 0});
        }
        CRC32 crc = new CRC32();
        crc.update(ihdr.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            data.writeInt(13);
            data.write(ihdr.toByteArray());
            data.writeInt((int) crc.getValue());
        }
        return out.toByteArray();
    }

    private static byte[] encoded(String format, int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    /** A real JPEG with its frame header rewritten to declare these dimensions. */
    private static byte[] jpegDeclaring(int width, int height) throws IOException {
        byte[] jpeg = encoded("jpeg", 8, 8);
        for (int i = 0; i < jpeg.length - 8; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xC0) {
                jpeg[i + 5] = (byte) (height >> 8);
                jpeg[i + 6] = (byte) height;
                jpeg[i + 7] = (byte) (width >> 8);
                jpeg[i + 8] = (byte) width;
                return jpeg;
            }
        }
        throw new IllegalStateException("No frame header in the encoded JPEG");
    }

    @Test
    void aSmallFileDeclaringEnormousDimensionsIsRefused() throws IOException {
        byte[] bomb = pngDeclaring(30_000, 30_000);

        assertThat(bomb).hasSizeLessThan(100);
        assertThat(ImageBounds.exceeded(bomb)).isTrue();
        assertThatThrownBy(() -> ImageBounds.require(bomb))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                .hasMessage("error.upload.too-large");
    }

    @Test
    void aPngJustInsideTheLimitPasses() throws IOException {
        assertThat(ImageBounds.exceeded(pngDeclaring(4000, 4000))).isFalse();
        assertThat(ImageBounds.exceeded(pngDeclaring(4001, 4000))).isTrue();
    }

    @Test
    void aFiftyMegapixelPhoneJpegPasses_andAPngOfThatSizeDoesNot() throws IOException {
        assertThat(ImageBounds.exceeded(jpegDeclaring(8160, 6120))).isFalse();
        assertThat(ImageBounds.exceeded(pngDeclaring(8160, 6120))).isTrue();
    }

    @Test
    void aJpegPastItsOwnLimitIsRefused() throws IOException {
        assertThat(ImageBounds.exceeded(jpegDeclaring(20_000, 20_000))).isTrue();
    }

    @Test
    void ordinaryImagesPass() throws IOException {
        assertThat(ImageBounds.exceeded(encoded("png", 64, 64))).isFalse();
        assertThat(ImageBounds.exceeded(encoded("jpeg", 64, 64))).isFalse();
    }

    @Test
    void bytesWhoseHeaderCannotBeReadAreNotItsVerdictToGive() {
        byte[] junkJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        byte[] notAnImage = "plain text".getBytes(StandardCharsets.UTF_8);

        assertThat(ImageBounds.exceeded(junkJpeg)).isFalse();
        assertThat(ImageBounds.exceeded(notAnImage)).isFalse();
        assertThatCode(() -> ImageBounds.require(junkJpeg)).doesNotThrowAnyException();
    }
}
