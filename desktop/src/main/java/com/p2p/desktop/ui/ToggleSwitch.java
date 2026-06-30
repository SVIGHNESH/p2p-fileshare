package com.p2p.desktop.ui;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * A compact iOS-style sliding on/off switch: a pill track with an animated thumb that slides between
 * the off (left, grey) and on (right, accent-green) positions. Built to replace the bare
 * {@code ToggleButton} that rendered the literal text "ON"/"OFF" in the Settings auto-discover row,
 * which looked unprofessional next to the rest of the chrome.
 *
 * <p>The control is driven entirely by an external {@link BooleanProperty} so the switch and the
 * backing app state stay in lockstep in both directions: a click flips the property, and any
 * programmatic change to the property (e.g. typing a manual tracker host flips auto-discover off)
 * animates the switch to match. There is no separate internal "selected" state to drift out of sync.
 */
public class ToggleSwitch extends StackPane {

    private static final double WIDTH = 46;
    private static final double HEIGHT = 26;
    private static final double PAD = 3;
    private static final double THUMB_RADIUS = (HEIGHT - 2 * PAD) / 2;
    /** How far the thumb travels from the off position to the on position. */
    private static final double TRAVEL = WIDTH - 2 * PAD - 2 * THUMB_RADIUS;

    private static final Color TRACK_ON = Color.web("#0E8C77");  // accent green, matches the chrome
    private static final Color TRACK_OFF = Color.web("#3A4654"); // muted slate

    private final Rectangle track = new Rectangle(WIDTH, HEIGHT);
    private final Circle thumb = new Circle(THUMB_RADIUS);
    private final BooleanProperty selected;

    public ToggleSwitch(BooleanProperty selected) {
        this.selected = selected;

        track.setArcWidth(HEIGHT);
        track.setArcHeight(HEIGHT);

        thumb.setFill(Color.WHITE);
        thumb.setEffect(new DropShadow(3, 0, 1, Color.rgb(0, 0, 0, 0.35)));

        setPrefSize(WIDTH, HEIGHT);
        setMinSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setAlignment(Pos.CENTER_LEFT);
        StackPane.setMargin(thumb, new Insets(0, 0, 0, PAD));
        getChildren().addAll(track, thumb);

        setCursor(Cursor.HAND);
        setOnMouseClicked(e -> selected.set(!selected.get()));
        selected.addListener((o, was, now) -> render(now, true));

        render(selected.get(), false);
    }

    private void render(boolean on, boolean animate) {
        track.setFill(on ? TRACK_ON : TRACK_OFF);
        double toX = on ? TRAVEL : 0;
        if (animate) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(140), thumb);
            tt.setToX(toX);
            tt.play();
        } else {
            thumb.setTranslateX(toX);
        }
    }
}
