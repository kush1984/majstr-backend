package com.majstr.backend.service;

import com.majstr.backend.storage.UnsupportedMediaTypeException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Refuses an image whose header declares more pixels than a heap can afford (review B-122). The size
 * cap on an upload says nothing here: a PNG of a few hundred kilobytes can declare enormous dimensions,
 * and OpenPDF decodes one with an alpha channel in full. A JPEG is embedded without decoding, so it gets
 * a limit a real camera fits under.
 *
 * <p>Reads headers only. A header it cannot read is not its verdict to give: the renderers already skip
 * an image that fails to load, so this answers {@code false} and leaves the bytes to them.</p>
 */
public final class ImageBounds {

    static final long MAX_JPEG_PIXELS = 60_000_000L;
    static final long MAX_PNG_PIXELS = 16_000_000L;

    private ImageBounds() {
    }

    /** At an upload: the bytes never reach storage. */
    public static void require(byte[] image) {
        if (exceeded(image)) {
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
    }

    /** At a render: rows stored before this check existed get the same answer. */
    public static boolean exceeded(byte[] image) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                long max = "png".equalsIgnoreCase(reader.getFormatName()) ? MAX_PNG_PIXELS : MAX_JPEG_PIXELS;
                return pixels > max;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
