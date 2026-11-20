package com.majstr.backend.service;

import io.nayuki.qrcodegen.QrCode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A QR code as a PNG — the format a bank app's «scan from a photo» reads, which an SVG is not.
 * Black on white with the four-module quiet zone the standard asks for.
 */
public final class QrPng {

    static final int SCALE = 8;
    static final int BORDER = 4;

    private QrPng() {}

    public static byte[] of(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int size = (qr.size + BORDER * 2) * SCALE;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = qr.getModule(x / SCALE - BORDER, y / SCALE - BORDER);
                image.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
