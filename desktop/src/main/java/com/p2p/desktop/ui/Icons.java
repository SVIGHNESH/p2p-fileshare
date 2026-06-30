package com.p2p.desktop.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * Crisp, dependency-free vector icons for the app chrome.
 *
 * <p>Path data is from the Feather icon set (MIT-licensed), drawn on a 24x24
 * viewBox with a 2px stroke. Rendering emoji glyphs in the navigation looked
 * unprofessional and broke across platforms (the download arrow collapsed to a
 * bare "|"); stroked vectors render identically everywhere and scale cleanly.
 *
 * <p>Each icon is a scaled {@link SVGPath} wrapped in a {@link Group}. The wrap
 * matters: a {@code Scale} placed in a node's own transforms does not change its
 * {@code layoutBounds}, so a layout container would still reserve the unscaled
 * 24px box. A Group's bounds are the union of its children's transformed bounds,
 * so the wrapper reports the real on-screen size and lays out correctly.
 */
public final class Icons {

    private Icons() {}

    public static final String SEARCH =
            "M3 11 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0 M16.65 16.65 L21 21";

    public static final String DOWNLOAD =
            "M21 15 v4 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 v-4 "
          + "M7 10 L12 15 L17 10 M12 15 V3";

    public static final String FOLDER =
            "M22 19 a2 2 0 0 1 -2 2 H4 a2 2 0 0 1 -2 -2 V5 a2 2 0 0 1 2 -2 "
          + "h5 l2 3 h9 a2 2 0 0 1 2 2 z";

    public static final String SETTINGS =
            "M19.4 15 a1.65 1.65 0 0 0 .33 1.82 l.06 .06 a2 2 0 0 1 0 2.83 "
          + "2 2 0 0 1 -2.83 0 l-.06 -.06 a1.65 1.65 0 0 0 -1.82 -.33 "
          + "1.65 1.65 0 0 0 -1 1.51 V21 a2 2 0 0 1 -2 2 2 2 0 0 1 -2 -2 "
          + "v-.09 A1.65 1.65 0 0 0 9 19.4 a1.65 1.65 0 0 0 -1.82 .33 "
          + "l-.06 .06 a2 2 0 0 1 -2.83 0 2 2 0 0 1 0 -2.83 l.06 -.06 "
          + "a1.65 1.65 0 0 0 .33 -1.82 1.65 1.65 0 0 0 -1.51 -1 H3 "
          + "a2 2 0 0 1 -2 -2 2 2 0 0 1 2 -2 h.09 A1.65 1.65 0 0 0 4.6 9 "
          + "a1.65 1.65 0 0 0 -.33 -1.82 l-.06 -.06 a2 2 0 0 1 0 -2.83 "
          + "2 2 0 0 1 2.83 0 l.06 .06 a1.65 1.65 0 0 0 1.82 .33 H9 "
          + "a1.65 1.65 0 0 0 1 -1.51 V3 a2 2 0 0 1 2 -2 2 2 0 0 1 2 2 "
          + "v.09 a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82 -.33 "
          + "l.06 -.06 a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83 l-.06 .06 "
          + "a1.65 1.65 0 0 0 -.33 1.82 V9 a1.65 1.65 0 0 0 1.51 1 H21 "
          + "a2 2 0 0 1 2 2 2 2 0 0 1 -2 2 h-.09 a1.65 1.65 0 0 0 -1.51 1 z "
          + "M15 12 A3 3 0 0 1 9 12 A3 3 0 0 1 15 12";

    public static final String LOCK =
            "M5 11 h14 a2 2 0 0 1 2 2 v7 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 "
          + "v-7 a2 2 0 0 1 2 -2 z M7 11 V7 a5 5 0 0 1 10 0 v4";

    /** Three connected nodes — the app's share/network mark. */
    public static final String SHARE =
            "M9 12 A3 3 0 1 1 3 12 A3 3 0 1 1 9 12 "
          + "M21 5 A3 3 0 1 1 15 5 A3 3 0 1 1 21 5 "
          + "M21 19 A3 3 0 1 1 15 19 A3 3 0 1 1 21 19 "
          + "M8.59 13.51 L15.42 17.49 M15.41 6.51 L8.59 10.49";

    // ── UI glyphs ────────────────────────────────────────────────────────────

    public static final String CHECK = "M20 6 L9 17 L4 12";

    public static final String PLUS = "M12 5 V19 M5 12 H19";

    public static final String REFRESH =
            "M23 4 V10 H17 M1 20 V14 H7 "
          + "M3.51 9 a9 9 0 0 1 14.85 -3.36 L23 10 "
          + "M1 14 l4.64 4.36 A9 9 0 0 0 20.49 15";

    public static final String INFO =
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0 M12 16 V12 M12 8 H12.01";

    public static final String GLOBE =
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0 M2 12 H22 "
          + "M12 2 a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1 -4 10 "
          + "15.3 15.3 0 0 1 -4 -10 15.3 15.3 0 0 1 4 -10 z";

    public static final String USER =
            "M20 21 v-2 a4 4 0 0 0 -4 -4 H8 a4 4 0 0 0 -4 4 v2 "
          + "M8 7 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0";

    public static final String ALERT =
            "M10.29 3.86 L1.82 18 a2 2 0 0 0 1.71 3 h16.94 a2 2 0 0 0 1.71 -3 "
          + "L13.71 3.86 a2 2 0 0 0 -3.42 0 z M12 9 V13 M12 17 H12.01";

    public static final String INBOX =
            "M22 12 H16 L14 15 H10 L8 12 H2 "
          + "M5.45 5.11 L2 12 v6 a2 2 0 0 0 2 2 h16 a2 2 0 0 0 2 -2 v-6 "
          + "l-3.45 -6.89 A2 2 0 0 0 16.76 4 H7.24 a2 2 0 0 0 -1.79 1.11 z";

    // ── File-type glyphs ─────────────────────────────────────────────────────

    public static final String FILE =
            "M13 2 H6 a2 2 0 0 0 -2 2 v16 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 V9 z "
          + "M13 2 V9 H20";

    public static final String FILE_TEXT =
            "M14 2 H6 a2 2 0 0 0 -2 2 v16 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 V8 z "
          + "M14 2 V8 H20 M16 13 H8 M16 17 H8 M10 9 H8";

    public static final String FILM =
            "M4.18 2 H19.82 a2.18 2.18 0 0 1 2.18 2.18 V19.82 "
          + "a2.18 2.18 0 0 1 -2.18 2.18 H4.18 a2.18 2.18 0 0 1 -2.18 -2.18 V4.18 "
          + "a2.18 2.18 0 0 1 2.18 -2.18 z "
          + "M7 2 V22 M17 2 V22 M2 12 H22 M2 7 H7 M2 17 H7 M17 17 H22 M17 7 H22";

    public static final String MUSIC =
            "M9 18 V5 l12 -2 v13 "
          + "M3 18 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0 "
          + "M15 16 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0";

    public static final String IMAGE =
            "M5 3 H19 a2 2 0 0 1 2 2 V19 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 V5 "
          + "a2 2 0 0 1 2 -2 z M7 8.5 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0 "
          + "M21 15 L16 10 L5 21";

    public static final String ARCHIVE =
            "M21 8 V21 H3 V8 M1 3 H23 V8 H1 z M10 12 H14";

    public static final String CODE = "M16 18 L22 12 L16 6 M8 6 L2 12 L8 18";

    /**
     * A configured, scaled {@link SVGPath}. Returned (rather than the wrapped
     * Group) when the caller needs to recolor it later — e.g. a nav icon that
     * tracks tab selection. Wrap it in a Group before adding it to a layout.
     */
    public static SVGPath path(String content, double size, Paint stroke, double strokeWidth) {
        SVGPath p = new SVGPath();
        p.setContent(content);
        p.setFill(null);
        p.setStroke(stroke);
        p.setStrokeWidth(strokeWidth);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        double k = size / 24.0;
        p.getTransforms().add(new Scale(k, k));
        return p;
    }

    /** A layout-ready icon node at {@code size} px, stroked in {@code color}. */
    public static Node icon(String content, double size, Paint color, double strokeWidth) {
        return new Group(path(content, size, color, strokeWidth));
    }

    /** A layout-ready icon node with the default 2px stroke. */
    public static Node icon(String content, double size, Paint color) {
        return icon(content, size, color, 2.0);
    }

    /** The vector glyph that best represents a file, chosen by its extension. */
    public static String fileGlyph(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(".pdf") || l.endsWith(".txt") || l.endsWith(".doc")
                || l.endsWith(".docx") || l.endsWith(".md") || l.endsWith(".rtf")) return FILE_TEXT;
        if (l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".avi")
                || l.endsWith(".mov") || l.endsWith(".webm")) return FILM;
        if (l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".flac")
                || l.endsWith(".ogg") || l.endsWith(".m4a")) return MUSIC;
        if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".svg")) return IMAGE;
        if (l.endsWith(".zip") || l.endsWith(".tar") || l.endsWith(".gz")
                || l.endsWith(".rar") || l.endsWith(".7z")) return ARCHIVE;
        if (l.endsWith(".java") || l.endsWith(".py") || l.endsWith(".js")
                || l.endsWith(".c") || l.endsWith(".cpp") || l.endsWith(".h")
                || l.endsWith(".ts") || l.endsWith(".go") || l.endsWith(".rs")) return CODE;
        return FILE;
    }

    /**
     * A layout-ready file-type icon, picked from {@code name}'s extension and
     * stroked in {@code color}. Replaces the per-view emoji {@code fileIcon()}
     * helpers so all three file lists render identical, consistent vectors.
     */
    public static Node fileIcon(String name, double size, Paint color) {
        return icon(fileGlyph(name), size, color, 1.8);
    }
}
