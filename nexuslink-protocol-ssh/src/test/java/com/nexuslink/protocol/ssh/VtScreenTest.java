package com.nexuslink.protocol.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VtScreenTest {

    private static final String ESC = "";

    @Test
    void printsPlainTextAndAdvancesCursor() {
        VtScreen s = new VtScreen(5, 20);
        s.feed("hello");
        assertEquals("hello", s.lineText(0));
        assertEquals(0, s.cursorRow());
        assertEquals(5, s.cursorCol());
    }

    @Test
    void carriageReturnAndLineFeed() {
        VtScreen s = new VtScreen(5, 20);
        s.feed("abc\r\ndef");
        assertEquals("abc", s.lineText(0));
        assertEquals("def", s.lineText(1));
        assertEquals(1, s.cursorRow());
    }

    @Test
    void backspaceMovesCursorLeftWithoutErasing() {
        VtScreen s = new VtScreen(3, 10);
        s.feed("abc\b");
        assertEquals(2, s.cursorCol());
        assertEquals("abc", s.lineText(0)); // BS alone does not erase; the glyph remains
    }

    @Test
    void tabAdvancesToNextEightColumnStop() {
        VtScreen s = new VtScreen(3, 40);
        s.feed("a\tb");
        assertEquals('a', s.cellAt(0, 0).ch());
        assertEquals('b', s.cellAt(0, 8).ch());
        assertEquals(9, s.cursorCol());
    }

    @Test
    void deferredWrapAtLastColumn() {
        VtScreen s = new VtScreen(3, 4);
        s.feed("abcd");        // fills the row; cursor parks on the last column (deferred wrap)
        assertEquals("abcd", s.lineText(0));
        assertEquals(0, s.cursorRow());
        s.feed("e");           // now it wraps to the next line
        assertEquals(1, s.cursorRow());
        assertEquals('e', s.cellAt(1, 0).ch());
    }

    @Test
    void cursorPositionAbsolute() {
        VtScreen s = new VtScreen(10, 40);
        s.feed(ESC + "[3;5Hx");   // row 3, col 5 (1-based)
        assertEquals('x', s.cellAt(2, 4).ch());
    }

    @Test
    void cursorMovementRelative() {
        VtScreen s = new VtScreen(10, 40);
        s.feed(ESC + "[5;5H");        // to (4,4)
        s.feed(ESC + "[2A");          // up 2  → row 2
        s.feed(ESC + "[3C");          // fwd 3 → col 7
        s.feed("z");
        assertEquals('z', s.cellAt(2, 7).ch());
    }

    @Test
    void eraseInLineToEnd() {
        VtScreen s = new VtScreen(3, 10);
        s.feed("abcdef");
        s.feed(ESC + "[4G");     // column 4 (1-based) → index 3
        s.feed(ESC + "[K");      // erase to end of line
        assertEquals("abc", s.lineText(0));
    }

    @Test
    void eraseInDisplayAll() {
        VtScreen s = new VtScreen(4, 10);
        s.feed("one\r\ntwo\r\nthree");
        s.feed(ESC + "[2J");
        assertEquals("", s.text().strip());
    }

    @Test
    void scrollsWhenLineFeedAtBottom() {
        VtScreen s = new VtScreen(3, 10);
        s.feed("l1\r\nl2\r\nl3");   // fills all three rows, cursor on last
        s.feed("\r\nl4");           // LF at the bottom scrolls the region up
        assertEquals("l2", s.lineText(0));
        assertEquals("l3", s.lineText(1));
        assertEquals("l4", s.lineText(2));
    }

    @Test
    void scrollRegionLimitsScrolling() {
        VtScreen s = new VtScreen(4, 10);
        s.feed(ESC + "[2;3r");   // scroll region = rows 2..3
        s.feed(ESC + "[2;1H");   // cursor to row 2
        s.feed("a\r\nb\r\nc");   // 'a' row2, 'b' row3, then LF at bottom scrolls region → 'c' row3
        assertEquals("", s.lineText(0));  // row 1 untouched by the region scroll
        assertEquals("b", s.lineText(1));
        assertEquals("c", s.lineText(2));
    }

    @Test
    void deleteAndInsertChars() {
        VtScreen s = new VtScreen(3, 10);
        s.feed("abcdef");
        s.feed(ESC + "[1G");        // back to column 1
        s.feed(ESC + "[2P");        // delete 2 chars → "cdef"
        assertEquals("cdef", s.lineText(0));
        s.feed(ESC + "[1G");
        s.feed(ESC + "[2@");        // insert 2 blanks → "  cdef"
        assertEquals("  cdef", s.lineText(0));
    }

    @Test
    void sgrColorsAreRecorded() {
        VtScreen s = new VtScreen(3, 20);
        s.feed(ESC + "[31mR" + ESC + "[1;32mG" + ESC + "[0mN");
        assertEquals(1, s.cellAt(0, 0).fg());          // red
        assertFalse(s.cellAt(0, 0).bold());
        assertEquals(2, s.cellAt(0, 1).fg());          // green
        assertTrue(s.cellAt(0, 1).bold());
        assertEquals(VtScreen.DEFAULT_COLOR, s.cellAt(0, 2).fg());  // reset
        assertFalse(s.cellAt(0, 2).bold());
    }

    @Test
    void sgr256AndBrightColors() {
        VtScreen s = new VtScreen(3, 20);
        s.feed(ESC + "[38;5;196mA");   // 256-colour index 196
        assertEquals(196, s.cellAt(0, 0).fg());
        s.feed(ESC + "[0m" + ESC + "[91mB");  // bright red → index 9
        assertEquals(9, s.cellAt(0, 1).fg());
    }

    @Test
    void inverseSwapsForegroundAndBackground() {
        VtScreen s = new VtScreen(3, 10);
        s.feed(ESC + "[31;42m" + ESC + "[7mX");   // fg=red(1), bg=green(2), inverse
        assertEquals(2, s.cellAt(0, 0).fg());       // shows bg colour as fg
        assertEquals(1, s.cellAt(0, 0).bg());
    }

    @Test
    void cursorVisibilityMode() {
        VtScreen s = new VtScreen(3, 10);
        assertTrue(s.cursorVisible());
        s.feed(ESC + "[?25l");
        assertFalse(s.cursorVisible());
        s.feed(ESC + "[?25h");
        assertTrue(s.cursorVisible());
    }

    @Test
    void alternateScreenBufferSwaps() {
        VtScreen s = new VtScreen(3, 10);
        s.feed("primary");
        s.feed(ESC + "[?1049h");        // enter alt screen
        assertEquals("", s.text().strip());
        s.feed("altcontent");
        assertEquals("altcontent", s.lineText(0));
        s.feed(ESC + "[?1049l");        // back to primary
        assertEquals("primary", s.lineText(0));
    }

    @Test
    void oscTitleIsSwallowed() {
        VtScreen s = new VtScreen(3, 20);
        s.feed(ESC + "]0;my window titlevisible");
        assertEquals("visible", s.lineText(0));
    }

    @Test
    void saveAndRestoreCursor() {
        VtScreen s = new VtScreen(5, 20);
        s.feed(ESC + "[3;3H");
        s.feed(ESC + "7");         // save
        s.feed(ESC + "[1;1Hx");
        s.feed(ESC + "8");         // restore → back to (2,2)
        s.feed("y");
        assertEquals('y', s.cellAt(2, 2).ch());
    }

    @Test
    void resizePreservesTopLeftContent() {
        VtScreen s = new VtScreen(3, 10);
        s.feed("keepme");
        s.resize(5, 20);
        assertEquals(5, s.rows());
        assertEquals(20, s.cols());
        assertEquals("keepme", s.lineText(0));
    }

    @Test
    void malformedSequencesDoNotThrow() {
        VtScreen s = new VtScreen(3, 10);
        assertDoesNotThrow(() -> s.feed(ESC + "[999999;Zjunk" + ESC + "[m" + ESC + "Xok"));
    }

    @Test
    void truecolorCollapsesToXterm256Index() {
        // Pure mapping seam: pure red → the 6x6x6 cube corner used by xterm-256.
        assertEquals(196, VtScreen.rgbToXterm256(255, 0, 0));
        assertEquals(16, VtScreen.rgbToXterm256(0, 0, 0));
        assertEquals(231, VtScreen.rgbToXterm256(255, 255, 255));
    }
}
