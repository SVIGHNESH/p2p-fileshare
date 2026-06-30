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
}
