package linview.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R-NAV-02: the folder's images, in natural name order; R-NAV-01: opening a file or folder. */
class ImageListTest {

    @TempDir
    Path folder;

    private static List<String> sorted(String... names) {
        List<String> list = new ArrayList<>(List.of(names));
        Collections.shuffle(list);
        list.sort(ImageList.NATURAL);
        return list;
    }

    @Test
    void numbersSortByValue() {
        assertEquals(List.of("img1.jpg", "img2.jpg", "img10.jpg", "img100.jpg"),
                sorted("img10.jpg", "img2.jpg", "img100.jpg", "img1.jpg"));
    }

    @Test
    void ignoresCase() {
        assertEquals(List.of("apple.jpg", "Banana.jpg", "cherry.jpg"),
                sorted("cherry.jpg", "Banana.jpg", "apple.jpg"));
    }

    @Test
    void cameraNamesWithLeadingZeros() {
        assertEquals(List.of("IMG_0001.JPG", "IMG_0002.JPG", "IMG_0010.JPG"),
                sorted("IMG_0010.JPG", "IMG_0001.JPG", "IMG_0002.JPG"));
    }

    @Test
    void numbersLongerThanALong() {
        assertEquals(List.of("scan99999999999999999999.png", "scan100000000000000000000.png"),
                sorted("scan100000000000000000000.png", "scan99999999999999999999.png"));
    }

    @Test
    void shorterNameFirst() {
        assertEquals(List.of("img.jpg", "img2.jpg", "img2b.jpg"), sorted("img2b.jpg", "img.jpg", "img2.jpg"));
    }

    @Test
    void equalNamesHaveAFixedOrder() {
        // Same name apart from case or leading zeros: always the same order, whatever order they arrive in.
        for (int run = 0; run < 20; run++) {
            assertEquals(List.of("IMG1.jpg", "img01.jpg", "img1.jpg"), sorted("img1.jpg", "IMG1.jpg", "img01.jpg"));
        }
    }

    @Test
    void listsOnlySupportedVisibleImagesSorted() throws IOException {
        for (String name : List.of("img10.jpg", "img2.JPG", "IMG_0001.jpeg", "b.png", "c.gif", "d.bmp",
                "e.webp", "f.TIF", "g.tiff", "h.psd", "i.tga", ".hidden.jpg", "notes.txt", "raw.cr2", "noextension", "jpg")) {
            Files.createFile(folder.resolve(name));
        }
        Files.createDirectory(folder.resolve("sub.jpg"));
        Files.createFile(folder.resolve("sub.jpg").resolve("inside.jpg"));

        List<String> names = ImageList.imagesIn(folder).stream().map(p -> p.getFileName().toString()).toList();

        assertEquals(List.of("b.png", "c.gif", "d.bmp", "e.webp", "f.TIF", "g.tiff", "h.psd", "i.tga",
                "img2.JPG", "img10.jpg", "IMG_0001.jpeg"), names);
    }

    @Test
    void emptyFolderGivesEmptyList() throws IOException {
        assertEquals(List.of(), ImageList.imagesIn(folder));
        assertTrue(ImageList.open(folder).isEmpty());
    }

    /** R-NAV-01: opening a file makes its folder the list, positioned on that file. */
    @Test
    void openingAFileListsItsFolderAtThatFile() throws IOException {
        for (String name : List.of("img1.jpg", "img2.jpg", "img10.jpg")) {
            Files.createFile(folder.resolve(name));
        }
        ImageList list = ImageList.open(folder.resolve("img2.jpg"));

        assertEquals(folder.resolve("img2.jpg").toAbsolutePath(), list.current());
        assertEquals(2, list.position());
        assertEquals(3, list.size());
        assertEquals(folder.toAbsolutePath(), list.folder());
    }

    @Test
    void openingARelativePathWorks() throws IOException {
        Files.createFile(folder.resolve("a.png"));
        Path relative = Path.of("").toAbsolutePath().relativize(folder.resolve("a.png").toAbsolutePath());

        assertEquals(1, ImageList.open(relative).position());
    }

    @Test
    void openingAFolderStartsAtItsFirstImage() throws IOException {
        for (String name : List.of("img10.jpg", "img2.jpg")) {
            Files.createFile(folder.resolve(name));
        }
        ImageList list = ImageList.open(folder);

        assertEquals("img2.jpg", list.current().getFileName().toString());
        assertEquals(1, list.position());
    }

    /** R-NAV-03, R-NAV-04: next/previous/first/last, stopping at the ends. */
    @Test
    void navigatesAndStopsAtTheEnds() throws IOException {
        for (String name : List.of("img1.jpg", "img2.jpg", "img10.jpg")) {
            Files.createFile(folder.resolve(name));
        }
        ImageList list = ImageList.open(folder.resolve("img2.jpg"));

        assertEquals(3, list.next().position());
        assertEquals(1, list.previous().position());
        assertEquals(1, list.first().position());
        assertEquals(3, list.last().position());

        ImageList last = list.last();
        assertSame(last, last.next(), "next at the last image stays there");
        assertSame(last, last.last());
        ImageList first = list.first();
        assertSame(first, first.previous(), "previous at the first image stays there");
        assertSame(first, first.first());
    }

    @Test
    void navigatingAnEmptyListDoesNothing() throws IOException {
        ImageList empty = ImageList.open(folder);

        assertSame(empty, empty.next());
        assertSame(empty, empty.previous());
        assertSame(empty, empty.first());
        assertSame(empty, empty.last());
    }

    @Test
    void missingPathIsReported() {
        assertThrows(NoSuchFileException.class, () -> ImageList.open(folder.resolve("gone.jpg")));
    }

    @Test
    void unsupportedAndHiddenFilesAreRefusedWithAMessage() throws IOException {
        Files.createFile(folder.resolve("notes.txt"));
        Files.createFile(folder.resolve(".secret.jpg"));

        IOException txt = assertThrows(IOException.class, () -> ImageList.open(folder.resolve("notes.txt")));
        assertEquals("Not a supported image: notes.txt", txt.getMessage());
        IOException hidden = assertThrows(IOException.class, () -> ImageList.open(folder.resolve(".secret.jpg")));
        assertEquals("Hidden files are not shown: .secret.jpg", hidden.getMessage());
    }
}
