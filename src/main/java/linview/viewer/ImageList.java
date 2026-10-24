package linview.viewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The supported images of one folder in natural name order (R-NAV-02), and which one is shown (R-NAV-01).
 * No UI, so it can be tested without a screen.
 */
public final class ImageList {

    /** Formats JavaFX reads on its own (R-FMT-01). #9 adds the TwelveMonkeys formats. */
    public static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "psd", "tga");

    /**
     * Natural, case-insensitive order: {@code img2} before {@code img10}. Digit runs compare by value, so
     * numbers of any length work. Names equal apart from case or leading zeros fall back to plain
     * {@code String} order, so the result never depends on the order the folder returned them in.
     */
    static final Comparator<String> NATURAL = ImageList::compareNatural;

    private final Path folder;
    private final List<Path> files;
    private final int index;

    private ImageList(Path folder, List<Path> files, int index) {
        this.folder = folder;
        this.files = files;
        this.index = index;
    }

    /**
     * Opens a folder (positioned on its first image) or an image file (its folder becomes the list,
     * positioned on that file). An empty folder gives an empty list.
     *
     * @throws NoSuchFileException if {@code path} doesn't exist
     * @throws IOException with a message for the user if the file isn't a supported image, or the folder can't be read
     */
    static ImageList open(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (Files.isDirectory(target)) {
            return new ImageList(target, imagesIn(target), 0);
        }
        if (!Files.exists(target)) {
            throw new NoSuchFileException(target.toString());
        }
        String name = target.getFileName().toString();
        if (!isImage(target)) {
            throw new IOException(name.startsWith(".")
                    ? "Hidden files are not shown: " + name
                    : "Not a supported image: " + name);
        }
        Path parent = target.getParent();
        List<Path> files = imagesIn(parent);
        return new ImageList(parent, files, files.indexOf(target));
    }

    Path folder() {
        return folder;
    }

    boolean isEmpty() {
        return files.isEmpty();
    }

    /** The image shown now; only valid when the list is not empty. */
    Path current() {
        return files.get(index);
    }

    /** 1-based position of {@link #current()}, for the title ("n/N"). */
    int position() {
        return index + 1;
    }

    int size() {
        return files.size();
    }

    /** The folder's images in order; the same list for every position in this folder (the thumbnail strip, #28). */
    List<Path> files() {
        return files;
    }

    /** The next image; at the last image it stays there (R-NAV-04), returning this same list. */
    ImageList next() {
        return at(index + 1);
    }

    /** The previous image; at the first image it stays there (R-NAV-04), returning this same list. */
    ImageList previous() {
        return at(index - 1);
    }

    ImageList first() {
        return at(0);
    }

    ImageList last() {
        return at(files.size() - 1);
    }

    /**
     * The images to preload (#21), most wanted first: {@code ahead} images in the direction of movement
     * ({@code forward}), then {@code behind} the other way. Stops at the ends of the folder.
     */
    List<Path> upcoming(boolean forward, int ahead, int behind) {
        int step = forward ? 1 : -1;
        List<Path> wanted = new ArrayList<>();
        for (int i = 1; i <= ahead; i++) {
            addIfInside(wanted, index + step * i);
        }
        for (int i = 1; i <= behind; i++) {
            addIfInside(wanted, index - step * i);
        }
        return wanted;
    }

    private void addIfInside(List<Path> wanted, int i) {
        if (i >= 0 && i < files.size()) {
            wanted.add(files.get(i));
        }
    }

    /** The image at {@code newIndex} (0-based), clamped to the folder. */
    ImageList at(int newIndex) {
        int clamped = Math.max(0, Math.min(newIndex, files.size() - 1));
        return clamped == index || files.isEmpty() ? this : new ImageList(folder, files, clamped);
    }

    /** The supported, non-hidden image files directly inside {@code folder}, sorted with {@link #NATURAL}. */
    static List<Path> imagesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(ImageList::isImage)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), NATURAL))
                    .toList();
        }
    }

    static boolean isImage(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return !name.startsWith(".")
                && dot > 0
                && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))
                && Files.isRegularFile(file);
    }

    private static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int endA = digitsEnd(a, i);
                int endB = digitsEnd(b, j);
                int cmp = compareNumbers(a.substring(i, endA), b.substring(j, endB));
                if (cmp != 0) {
                    return cmp;
                }
                i = endA;
                j = endB;
            } else {
                int cmp = Character.compare(fold(ca), fold(cb));
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        int cmp = Integer.compare(a.length() - i, b.length() - j);
        return cmp != 0 ? cmp : a.compareTo(b);
    }

    private static int digitsEnd(String s, int start) {
        int end = start;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        return end;
    }

    /** Compares two digit strings by value without parsing, so any length works. */
    private static int compareNumbers(String x, String y) {
        String a = stripLeadingZeros(x);
        String b = stripLeadingZeros(y);
        int cmp = Integer.compare(a.length(), b.length());
        return cmp != 0 ? cmp : a.compareTo(b);
    }

    private static String stripLeadingZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }

    private static char fold(char c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }
}
