package com.nexuslink.protocol.ssh;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A pure, dependency-free VT100/xterm screen-buffer model — the offline-testable core of the SSH
 * terminal. Bytes from the remote shell are {@link #feed(byte[], int) fed} in and interpreted into a
 * fixed {@code rows x cols} grid of {@link Cell}s with a cursor; the JavaFX terminal view then just
 * paints the grid.
 *
 * <p>It implements the subset of control codes and CSI/SGR escape sequences that an interactive shell
 * actually leans on: printable text with auto-wrap, CR/LF/BS/TAB/BEL, cursor movement (CUU/CUD/CUF/CUB/
 * CNL/CPL/CHA/CUP/HVP/VPA), erase-in-display (ED) and erase-in-line (EL), insert/delete/erase char
 * (ICH/DCH/ECH), a scrolling region (DECSTBM) with index/reverse-index scrolling, save/restore cursor,
 * SGR colours (16-colour + bright + xterm-256, bold/inverse/reset), cursor-visibility and the alternate
 * screen buffer (?1049/?1047/?47). OSC (window-title) and unknown sequences are parsed and ignored
 * rather than leaking as garbage. Anything malformed is dropped defensively; the model never throws.
 */
public final class VtScreen {

    /** Default foreground / background: "use the view's theme default". */
    public static final int DEFAULT_COLOR = -1;

    /** One character cell: the glyph plus its 256-colour foreground/background and bold flag. */
    public record Cell(char ch, int fg, int bg, boolean bold) {
        static final Cell BLANK = new Cell(' ', DEFAULT_COLOR, DEFAULT_COLOR, false);
    }

    private int rows;
    private int cols;
    private Cell[][] grid;
    private Cell[][] altGrid;      // populated while the alternate screen buffer is active
    private boolean alternate;

    private int cursorRow;
    private int cursorCol;
    private int savedRow;
    private int savedCol;
    private boolean wrapPending;   // xterm deferred-wrap: cursor "past" the last column

    private int scrollTop;         // inclusive, 0-based
    private int scrollBottom;      // inclusive, 0-based

    private boolean cursorVisible = true;

    // Current SGR state.
    private int fg = DEFAULT_COLOR;
    private int bg = DEFAULT_COLOR;
    private boolean bold;
    private boolean inverse;

    // Escape-sequence parser state.
    private enum State { GROUND, ESC, CSI, OSC, OSC_ESC, CHARSET }
    private State state = State.GROUND;
    private final StringBuilder csi = new StringBuilder();

    private long revision;         // bumped on every mutation so a renderer can skip idle repaints

    public VtScreen(int rows, int cols) {
        this.rows = Math.max(1, rows);
        this.cols = Math.max(1, cols);
        this.grid = blankGrid(this.rows, this.cols);
        this.scrollTop = 0;
        this.scrollBottom = this.rows - 1;
    }

    // ---- accessors (used by the renderer) ----

    public int rows() { return rows; }
    public int cols() { return cols; }
    public int cursorRow() { return cursorRow; }
    public int cursorCol() { return Math.min(cursorCol, cols - 1); }
    public boolean cursorVisible() { return cursorVisible; }
    public long revision() { return revision; }

    public Cell cellAt(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return Cell.BLANK;
        return grid[row][col];
    }

    /** The plain text of a row, right-trimmed of trailing spaces (handy for assertions and copy). */
    public String lineText(int row) {
        if (row < 0 || row >= rows) return "";
        StringBuilder sb = new StringBuilder(cols);
        for (int c = 0; c < cols; c++) sb.append(grid[row][c].ch());
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /** The whole screen as newline-joined text (each line right-trimmed). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            if (r > 0) sb.append('\n');
            sb.append(lineText(r));
        }
        return sb.toString();
    }

    // ---- input ----

    /** Feeds decoded text (convenience for tests). */
    public void feed(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        feed(b, b.length);
    }

    /** Feeds {@code len} bytes of remote output through the terminal state machine. */
    public void feed(byte[] data, int len) {
        // Decode as UTF-8 for the whole chunk; control bytes are ASCII so this is safe for the parser.
        String s = new String(data, 0, len, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) process(s.charAt(i));
        revision++;
    }

    private void process(char c) {
        switch (state) {
            case GROUND -> ground(c);
            case ESC -> esc(c);
            case CSI -> csi(c);
            case OSC -> osc(c);
            case OSC_ESC -> { // saw ESC inside an OSC string: ST is ESC '\'
                state = (c == '\\') ? State.GROUND : State.OSC;
            }
            case CHARSET -> state = State.GROUND; // consume the single charset-designator byte
        }
    }

    private void ground(char c) {
        switch (c) {
            case 0x1b -> state = State.ESC;
            case '\r' -> { cursorCol = 0; wrapPending = false; }
            case '\n', 0x0b, 0x0c -> lineFeed();
            case '\b' -> { if (cursorCol > 0) cursorCol--; wrapPending = false; }
            case '\t' -> { cursorCol = Math.min(cols - 1, (cursorCol / 8 + 1) * 8); wrapPending = false; }
            case 0x07 -> { /* BEL — no visual bell */ }
            default -> { if (c >= 0x20) put(c); }
        }
    }

    private void esc(char c) {
        switch (c) {
            case '[' -> { csi.setLength(0); state = State.CSI; }
            case ']' -> state = State.OSC;
            case '(', ')', '*', '+' -> state = State.CHARSET;
            case 'M' -> { reverseIndex(); state = State.GROUND; }
            case 'D' -> { lineFeed(); state = State.GROUND; }
            case 'E' -> { cursorCol = 0; lineFeed(); state = State.GROUND; }
            case '7' -> { saveCursor(); state = State.GROUND; }
            case '8' -> { restoreCursor(); state = State.GROUND; }
            case 'c' -> { fullReset(); state = State.GROUND; }
            case '=', '>' -> state = State.GROUND; // keypad application/normal — ignore
            default -> state = State.GROUND;
        }
    }

    private void osc(char c) {
        if (c == 0x07) state = State.GROUND;        // BEL terminates OSC
        else if (c == 0x1b) state = State.OSC_ESC;  // ESC '\' (ST) terminates OSC
        // else swallow the title string
    }

    private void csi(char c) {
        if ((c >= '0' && c <= '9') || c == ';' || c == '?' || c == ':' || c == ' '
                || c == '<' || c == '=' || c == '>' || c == '!') {
            csi.append(c);
            if (csi.length() < 64) return; // keep collecting; guard against runaway
            state = State.GROUND;
            return;
        }
        // c is the final byte of the sequence.
        dispatchCsi(c, csi.toString());
        state = State.GROUND;
    }

    // ---- CSI dispatch ----

    private void dispatchCsi(char finalByte, String body) {
        boolean priv = body.startsWith("?");
        String params = priv ? body.substring(1) : body;
        switch (finalByte) {
            case 'A' -> moveCursor(-arg(params, 0, 1), 0);
            case 'B' -> moveCursor(arg(params, 0, 1), 0);
            case 'C' -> moveCursor(0, arg(params, 0, 1));
            case 'D' -> moveCursor(0, -arg(params, 0, 1));
            case 'E' -> { cursorCol = 0; moveCursor(arg(params, 0, 1), 0); }
            case 'F' -> { cursorCol = 0; moveCursor(-arg(params, 0, 1), 0); }
            case 'G', '`' -> { cursorCol = clampCol(arg(params, 0, 1) - 1); wrapPending = false; }
            case 'd' -> { cursorRow = clampRow(arg(params, 0, 1) - 1); }
            case 'H', 'f' -> {
                cursorRow = clampRow(arg(params, 0, 1) - 1);
                cursorCol = clampCol(arg(params, 1, 1) - 1);
                wrapPending = false;
            }
            case 'J' -> eraseDisplay(arg(params, 0, 0));
            case 'K' -> eraseLine(arg(params, 0, 0));
            case 'P' -> deleteChars(arg(params, 0, 1));
            case '@' -> insertBlanks(arg(params, 0, 1));
            case 'X' -> eraseChars(arg(params, 0, 1));
            case 'L' -> insertLines(arg(params, 0, 1));
            case 'M' -> deleteLines(arg(params, 0, 1));
            case 'S' -> { for (int i = 0; i < arg(params, 0, 1); i++) scrollUp(); }
            case 'T' -> { for (int i = 0; i < arg(params, 0, 1); i++) scrollDown(); }
            case 'r' -> setScrollRegion(params);
            case 'm' -> applySgr(params);
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 'h' -> setMode(priv, params, true);
            case 'l' -> setMode(priv, params, false);
            default -> { /* unsupported — ignore */ }
        }
    }

    // ---- printing ----

    private void put(char c) {
        if (wrapPending) {                 // deferred wrap from the previous glyph at the last column
            cursorCol = 0;
            lineFeed();
            wrapPending = false;
        }
        grid[cursorRow][cursorCol] = new Cell(c, effFg(), effBg(), bold);
        if (cursorCol == cols - 1) {
            wrapPending = true;            // stay on the last column until the next glyph forces a wrap
        } else {
            cursorCol++;
        }
    }

    private int effFg() { return inverse ? bgOr(DEFAULT_COLOR) : fg; }
    private int effBg() { return inverse ? fgOr(DEFAULT_COLOR) : bg; }
    private int bgOr(int d) { return bg == DEFAULT_COLOR ? d : bg; }
    private int fgOr(int d) { return fg == DEFAULT_COLOR ? d : fg; }

    // ---- cursor / scrolling ----

    private void moveCursor(int dRow, int dCol) {
        cursorRow = clampRow(cursorRow + dRow);
        cursorCol = clampCol(cursorCol + dCol);
        wrapPending = false;
    }

    private void lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp();
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    private void reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown();
        } else if (cursorRow > 0) {
            cursorRow--;
        }
    }

    /** Scrolls the scroll region up by one line, blanking the newly exposed bottom line. */
    private void scrollUp() {
        for (int r = scrollTop; r < scrollBottom; r++) grid[r] = grid[r + 1];
        grid[scrollBottom] = blankRow();
    }

    /** Scrolls the scroll region down by one line, blanking the newly exposed top line. */
    private void scrollDown() {
        for (int r = scrollBottom; r > scrollTop; r--) grid[r] = grid[r - 1];
        grid[scrollTop] = blankRow();
    }

    private void insertLines(int n) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
        for (int k = 0; k < n; k++) {
            for (int r = scrollBottom; r > cursorRow; r--) grid[r] = grid[r - 1];
            grid[cursorRow] = blankRow();
        }
    }

    private void deleteLines(int n) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
        for (int k = 0; k < n; k++) {
            for (int r = cursorRow; r < scrollBottom; r++) grid[r] = grid[r + 1];
            grid[scrollBottom] = blankRow();
        }
    }

    private void setScrollRegion(String params) {
        int top = arg(params, 0, 1) - 1;
        int bottom = arg(params, 1, rows) - 1;
        if (top < 0) top = 0;
        if (bottom >= rows) bottom = rows - 1;
        if (top >= bottom) { top = 0; bottom = rows - 1; }
        scrollTop = top;
        scrollBottom = bottom;
        cursorRow = scrollTop;
        cursorCol = 0;
        wrapPending = false;
    }

    private void saveCursor() { savedRow = cursorRow; savedCol = cursorCol; }
    private void restoreCursor() {
        cursorRow = clampRow(savedRow);
        cursorCol = clampCol(savedCol);
        wrapPending = false;
    }

    // ---- erase / edit ----

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0 -> { eraseLine(0); for (int r = cursorRow + 1; r < rows; r++) grid[r] = blankRow(); }
            case 1 -> { for (int r = 0; r < cursorRow; r++) grid[r] = blankRow(); eraseLine(1); }
            default -> { for (int r = 0; r < rows; r++) grid[r] = blankRow(); }  // 2 and 3
        }
    }

    private void eraseLine(int mode) {
        int from = switch (mode) { case 1 -> 0; case 2 -> 0; default -> cursorCol; };
        int to = switch (mode) { case 1 -> cursorCol; default -> cols - 1; };
        for (int c = from; c <= to && c < cols; c++) grid[cursorRow][c] = Cell.BLANK;
    }

    private void eraseChars(int n) {
        for (int c = cursorCol; c < cursorCol + n && c < cols; c++) grid[cursorRow][c] = Cell.BLANK;
    }

    private void deleteChars(int n) {
        Cell[] row = grid[cursorRow];
        for (int c = cursorCol; c < cols; c++) {
            int src = c + n;
            row[c] = src < cols ? row[src] : Cell.BLANK;
        }
    }

    private void insertBlanks(int n) {
        Cell[] row = grid[cursorRow];
        for (int c = cols - 1; c >= cursorCol; c--) {
            int src = c - n;
            row[c] = src >= cursorCol ? row[src] : Cell.BLANK;
        }
    }

    // ---- SGR (colours / attributes) ----

    private void applySgr(String params) {
        if (params.isEmpty()) { resetSgr(); return; }
        String[] parts = params.split(";");
        for (int i = 0; i < parts.length; i++) {
            int code = parseInt(parts[i], 0);
            switch (code) {
                case 0 -> resetSgr();
                case 1 -> bold = true;
                case 22 -> bold = false;
                case 7 -> inverse = true;
                case 27 -> inverse = false;
                case 39 -> fg = DEFAULT_COLOR;
                case 49 -> bg = DEFAULT_COLOR;
                case 38 -> i = extendedColor(parts, i, true);
                case 48 -> i = extendedColor(parts, i, false);
                default -> {
                    if (code >= 30 && code <= 37) fg = code - 30;
                    else if (code >= 40 && code <= 47) bg = code - 40;
                    else if (code >= 90 && code <= 97) fg = code - 90 + 8;
                    else if (code >= 100 && code <= 107) bg = code - 100 + 8;
                }
            }
        }
    }

    /** Handles {@code 38;5;n} / {@code 48;5;n} (256-colour) and {@code 38;2;r;g;b} (truecolour→approx). */
    private int extendedColor(String[] parts, int i, boolean foreground) {
        int mode = i + 1 < parts.length ? parseInt(parts[i + 1], -1) : -1;
        if (mode == 5 && i + 2 < parts.length) {
            int idx = parseInt(parts[i + 2], DEFAULT_COLOR);
            if (foreground) fg = idx; else bg = idx;
            return i + 2;
        }
        if (mode == 2 && i + 4 < parts.length) {
            // Collapse 24-bit to the nearest xterm-256 index so the model stays palette-indexed.
            int idx = rgbToXterm256(parseInt(parts[i + 2], 0), parseInt(parts[i + 3], 0), parseInt(parts[i + 4], 0));
            if (foreground) fg = idx; else bg = idx;
            return i + 4;
        }
        return i;
    }

    static int rgbToXterm256(int r, int g, int b) {
        int ri = colorCube(r), gi = colorCube(g), bi = colorCube(b);
        return 16 + 36 * ri + 6 * gi + bi;
    }

    private static int colorCube(int v) {
        v = Math.max(0, Math.min(255, v));
        if (v < 48) return 0;
        if (v < 115) return 1;
        return (v - 35) / 40;
    }

    private void resetSgr() { fg = DEFAULT_COLOR; bg = DEFAULT_COLOR; bold = false; inverse = false; }

    // ---- modes (DECSET/DECRST + a few others) ----

    private void setMode(boolean priv, String params, boolean set) {
        if (!priv) return; // ANSI modes (e.g. IRM) — not modelled
        for (String p : params.split(";")) {
            switch (parseInt(p, -1)) {
                case 25 -> cursorVisible = set;                 // DECTCEM
                case 47, 1047, 1049 -> useAlternate(set);       // alternate screen buffer
                default -> { /* other private modes ignored */ }
            }
        }
    }

    private void useAlternate(boolean enable) {
        if (enable == alternate) return;
        if (enable) {
            saveCursor();
            altGrid = blankGrid(rows, cols);
            Cell[][] primary = grid;
            grid = altGrid;
            altGrid = primary;            // stash the primary buffer to restore later
            alternate = true;
            resetScreenPosition();
        } else {
            grid = altGrid;               // swap the primary buffer back in
            altGrid = null;
            alternate = false;
            restoreCursor();
        }
    }

    private void resetScreenPosition() {
        cursorRow = 0; cursorCol = 0; wrapPending = false;
        scrollTop = 0; scrollBottom = rows - 1;
    }

    private void fullReset() {
        grid = blankGrid(rows, cols);
        altGrid = null;
        alternate = false;
        resetScreenPosition();
        resetSgr();
        cursorVisible = true;
        savedRow = savedCol = 0;
    }

    // ---- resize ----

    /** Resizes the grid, preserving the top-left content, and clamps the cursor and scroll region. */
    public void resize(int newRows, int newCols) {
        newRows = Math.max(1, newRows);
        newCols = Math.max(1, newCols);
        if (newRows == rows && newCols == cols) return;
        grid = copyInto(grid, newRows, newCols);
        if (altGrid != null) altGrid = copyInto(altGrid, newRows, newCols);
        rows = newRows;
        cols = newCols;
        scrollTop = 0;
        scrollBottom = rows - 1;
        cursorRow = clampRow(cursorRow);
        cursorCol = clampCol(cursorCol);
        wrapPending = false;
        revision++;
    }

    private static Cell[][] copyInto(Cell[][] src, int newRows, int newCols) {
        Cell[][] dst = new Cell[newRows][newCols];
        for (int r = 0; r < newRows; r++) {
            for (int c = 0; c < newCols; c++) {
                dst[r][c] = (r < src.length && c < src[r].length) ? src[r][c] : Cell.BLANK;
            }
        }
        return dst;
    }

    // ---- helpers ----

    private Cell[][] blankGrid(int r, int c) {
        Cell[][] g = new Cell[r][c];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) g[i][j] = Cell.BLANK;
        return g;
    }

    private Cell[] blankRow() {
        Cell[] row = new Cell[cols];
        for (int c = 0; c < cols; c++) row[c] = Cell.BLANK;
        return row;
    }

    private int clampRow(int r) { return Math.max(0, Math.min(rows - 1, r)); }
    private int clampCol(int c) { return Math.max(0, Math.min(cols - 1, c)); }

    /** Reads the {@code index}-th {@code ;}-separated parameter, defaulting when absent or 0/empty. */
    private static int arg(String params, int index, int def) {
        if (params.isEmpty()) return def;
        String[] parts = params.split(";");
        if (index >= parts.length) return def;
        int v = parseInt(parts[index], 0);
        return v == 0 ? def : v;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** Splits accumulated screen text into non-empty lines (test/debug convenience). */
    public List<String> nonEmptyLines() {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String t = lineText(r);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
