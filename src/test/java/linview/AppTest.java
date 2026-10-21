package linview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Placeholder so the test stage of the pipeline runs from day one. */
class AppTest {

    @Test
    void titleIsAppName() {
        assertEquals("linview", App.TITLE);
    }
}
