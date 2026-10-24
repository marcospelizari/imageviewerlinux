package linview.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * The freedesktop thumbnail cache (Thumbnail Managing Standard), shared with file managers such as Dolphin (#28):
 * {@code ~/.cache/thumbnails/large/<md5 of the file's URI>.png}, at most {@link #LARGE} pixels on the longer side,
 * valid while its {@code Thumb::MTime} equals the file's modified time in seconds. No JavaFX, so it can be tested
 * without a screen.
 */
final class ThumbnailCache {

    static final int LARGE = 256;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final Path large;

    /** {@code thumbnails}: the cache's root folder, see {@link #defaultFolder}. */
    ThumbnailCache(Path thumbnails) {
        this.large = thumbnails.resolve("large");
    }

    /** {@code $XDG_CACHE_HOME/thumbnails}, by default {@code ~/.cache/thumbnails}. */
    static Path defaultFolder() {
        String cache = System.getenv("XDG_CACHE_HOME");
        Path root = cache != null && !cache.isBlank()
                ? Path.of(cache) : Path.of(System.getProperty("user.home"), ".cache");
        return root.resolve("thumbnails");
    }

    /** The URI the standard hashes: {@code file:///…}, with spaces and non-ASCII characters %-escaped. */
    static String uri(Path file) {
        return file.toAbsolutePath().normalize().toUri().toASCIIString();
    }

    static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is always available", e);
        }
    }

    Path pathFor(Path file) {
        return large.resolve(md5(uri(file)) + ".png");
    }

    /** The cached thumbnail of {@code file} if it is still valid for {@code modifiedSeconds}, else {@code null}. */
    Path find(Path file, long modifiedSeconds) {
        Path thumbnail = pathFor(file);
        try (InputStream in = Files.newInputStream(thumbnail)) {
            Map<String, String> text = textChunks(in.readNBytes(64 * 1024));   // the chunks come before the pixels
            return uri(file).equals(text.get("Thumb::URI"))
                    && String.valueOf(modifiedSeconds).equals(text.get("Thumb::MTime")) ? thumbnail : null;
        } catch (IOException e) {
            return null;   // none yet, or unreadable: made again
        }
    }

    /**
     * Writes {@code thumbnail} for {@code file}: readable by its owner only, and replaced in one step, so a file
     * manager reading at the same time never sees half a file.
     */
    void store(Path file, long modifiedSeconds, BufferedImage thumbnail) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(thumbnail, "png", png)) {
            throw new IOException("no PNG writer");
        }
        byte[] tagged = withText(png.toByteArray(), Map.of(
                "Thumb::URI", uri(file),
                "Thumb::MTime", String.valueOf(modifiedSeconds),
                "Software", "linview"));
        if (!Files.isDirectory(large)) {
            Files.createDirectories(large,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Path temporary = Files.createTempFile(large, "linview-", ".png",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, tagged);
            Files.move(temporary, pathFor(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The {@code tEXt} chunks of a PNG, read up to the first image data; empty if it isn't a PNG. */
    static Map<String, String> textChunks(byte[] png) {
        Map<String, String> text = new HashMap<>();
        if (png.length < 8 || !Arrays.equals(png, 0, 8, PNG_SIGNATURE, 0, 8)) {
            return text;
        }
        int at = 8;
        while (at + 8 <= png.length) {
            int length = readInt(png, at);
            String type = new String(png, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("IDAT") || length < 0 || at + 12 + length > png.length) {
                break;
            }
            if (type.equals("tEXt")) {
                int start = at + 8;
                int zero = start;
                while (zero < start + length && png[zero] != 0) {
                    zero++;
                }
                if (zero < start + length) {
                    text.put(new String(png, start, zero - start, StandardCharsets.ISO_8859_1),
                            new String(png, zero + 1, start + length - zero - 1, StandardCharsets.ISO_8859_1));
                }
            }
            at += 12 + length;
        }
        return text;
    }

    /** {@code png} with a {@code tEXt} chunk per entry, right after the header chunk. */
    static byte[] withText(byte[] png, Map<String, String> entries) {
        int afterHeader = 8 + 12 + readInt(png, 8);   // signature, then IHDR
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
        out.write(png, 0, afterHeader);
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] data = (entry.getKey() + '\0' + entry.getValue()).getBytes(StandardCharsets.ISO_8859_1);
            byte[] type = "tEXt".getBytes(StandardCharsets.ISO_8859_1);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(data);
            writeInt(out, data.length);
            out.write(type, 0, 4);
            out.write(data, 0, data.length);
            writeInt(out, (int) crc.getValue());
        }
        out.write(png, afterHeader, png.length - afterHeader);
        return out.toByteArray();
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
