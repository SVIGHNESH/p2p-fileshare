package com.p2p.desktop.ui;

import com.p2p.desktop.AppState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.function.UnaryOperator;

public class SettingsView {

    private static final String SAVE_IDLE_STYLE =
            "-fx-background-color: #0E8C77; -fx-text-fill: white; -fx-background-radius: 10; -fx-cursor: hand;";
    private static final String SAVE_BUSY_STYLE =
            "-fx-background-color: #26313C; -fx-text-fill: #CBD5DF; -fx-background-radius: 10;";

    public Node build() {
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0E1419; -fx-background-color: #0E1419; -fx-border-color: transparent;");

        VBox root = new VBox(24);
        root.setPadding(new Insets(32));
        root.setStyle("-fx-background-color: #0E1419;");
        root.setMaxWidth(720);

        Label pageTitle = new Label("Settings");
        pageTitle.setFont(Font.font("System", FontWeight.BOLD, 24));
        pageTitle.setTextFill(Color.WHITE);

        AppState state = AppState.get();

        // ── Network Section ──────────────────────────────────────────────────
        root.getChildren().addAll(
                pageTitle,
                buildSectionHeader(Icons.GLOBE, "Network - Tracker Connection"),
                buildInfoBox(
                        "What is a Tracker?",
                        "The Tracker is a small program that keeps track of who has which files. " +
                        "One person in your group needs to run it (see the README). " +
                        "If auto-discovery is enabled, you don't need to type anything - " +
                        "the tracker will be found automatically when you're on the same Wi-Fi."),
                buildAutoDiscoveryCard(state),
                buildTrackerManualCard(state),

                buildSectionHeader(Icons.FOLDER, "Shared Folder"),
                buildInfoBox(
                        "Your shared folder",
                        "Files you place in this folder are visible to others on the network. " +
                        "Downloaded files are also saved here."),
                buildFolderCard(state),

                buildSectionHeader(Icons.USER, "Your Profile"),
                buildProfileCard(state),

                buildSectionHeader(Icons.LOCK, "Security"),
                buildSecurityCard(),

                buildSaveButton(state),

                buildSectionHeader(Icons.INFO, "About"),
                buildAboutCard()
        );

        scroll.setContent(root);
        return scroll;
    }

    private Node buildAboutCard() {
        VBox card = new VBox(6);
        card.setPadding(new Insets(14, 18, 14, 18));
        card.setStyle(
                "-fx-background-color: #101A20; -fx-background-radius: 8; " +
                "-fx-border-color: #24414F; -fx-border-radius: 8; -fx-border-width: 1;");
        Label creator = new Label("Created by Vighnesh Shukla");
        creator.setTextFill(Color.web("#93A1AE"));
        creator.setFont(Font.font("System", 13));
        card.getChildren().add(creator);
        return card;
    }

    private Node buildSectionHeader(String iconPath, String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, 16));
        label.setTextFill(Color.web("#B3C0CC"));
        HBox titleRow = new HBox(10, Icons.icon(iconPath, 17, Color.web("#B3C0CC"), 2.0), label);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #26313C;");
        VBox box = new VBox(8, titleRow, sep);
        return box;
    }

    private Node buildInfoBox(String title, String body) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(14, 18, 14, 18));
        box.setStyle(
                "-fx-background-color: #101A20; -fx-background-radius: 8; " +
                "-fx-border-color: #24414F; -fx-border-radius: 8; -fx-border-width: 1;");
        Label t = new Label(title);
        t.setFont(Font.font("System", FontWeight.BOLD, 13));
        t.setTextFill(Color.web("#58A8FF"));
        HBox titleRow = new HBox(8, Icons.icon(Icons.INFO, 14, Color.web("#58A8FF"), 2.0), t);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label b = new Label(body);
        b.setTextFill(Color.web("#93A1AE"));
        b.setFont(Font.font("System", 13));
        b.setWrapText(true);
        box.getChildren().addAll(titleRow, b);
        return box;
    }

    private Node buildAutoDiscoveryCard(AppState state) {
        HBox card = new HBox(16);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("-fx-background-color: #161F28; -fx-background-radius: 10; -fx-border-color: #26313C; -fx-border-radius: 10; -fx-border-width: 1;");

        VBox text = new VBox(4);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label t = new Label("Auto-discover Tracker on Wi-Fi");
        t.setFont(Font.font("System", FontWeight.BOLD, 14));
        t.setTextFill(Color.WHITE);
        Label b = new Label("The app will automatically find the tracker on your network. Recommended.");
        b.setTextFill(Color.web("#93A1AE"));
        b.setFont(Font.font("System", 13));
        b.setWrapText(true);
        text.getChildren().addAll(t, b);

        ToggleButton toggle = new ToggleButton(state.trackerHost.get().isEmpty() ? "ON" : "OFF");
        toggle.setSelected(state.trackerHost.get().isEmpty());
        toggle.setFont(Font.font("System", FontWeight.BOLD, 13));
        toggle.setStyle(toggle.isSelected()
                ? "-fx-background-color: #0E8C77; -fx-text-fill: white; -fx-background-radius: 20;"
                : "-fx-background-color: #26313C; -fx-text-fill: #888; -fx-background-radius: 20;");
        toggle.selectedProperty().addListener((o, ov, nv) -> {
            toggle.setText(nv ? "ON" : "OFF");
            toggle.setStyle(nv
                    ? "-fx-background-color: #0E8C77; -fx-text-fill: white; -fx-background-radius: 20;"
                    : "-fx-background-color: #26313C; -fx-text-fill: #888; -fx-background-radius: 20;");
            if (nv) state.trackerHost.set(""); // Clear manual host → enable autodiscovery
        });

        card.getChildren().addAll(text, toggle);
        return card;
    }

    private Node buildTrackerManualCard(AppState state) {
        VBox card = new VBox(14);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle("-fx-background-color: #161F28; -fx-background-radius: 10; -fx-border-color: #26313C; -fx-border-radius: 10; -fx-border-width: 1;");

        Label heading = new Label("Or enter the tracker address manually:");
        heading.setFont(Font.font("System", FontWeight.BOLD, 14));
        heading.setTextFill(Color.WHITE);

        HBox fields = new HBox(12);
        fields.setAlignment(Pos.CENTER_LEFT);

        TextField hostField = new TextField(state.trackerHost.get());
        hostField.setPromptText("Tracker IP address (e.g. 192.168.1.10)");
        hostField.setFont(Font.font("System", 14));
        hostField.setPrefHeight(40);
        HBox.setHgrow(hostField, Priority.ALWAYS);
        hostField.setStyle(fieldStyle());
        hostField.textProperty().bindBidirectional(state.trackerHost);

        TextField portField = new TextField();
        portField.setPromptText("Port");
        portField.setPrefWidth(90);
        portField.setPrefHeight(40);
        portField.setFont(Font.font("System", 14));
        portField.setStyle(fieldStyle());
        // DT.5: reject any keystroke that would make the field hold a non-numeric or out-of-range
        // port, so it can never contain "9000abc" (which would blow up Integer.parseInt on the Save
        // worker) nor a value like "70000" that the field shows but the app silently clamps to the
        // default. The filter vetoes the change before it lands, composing with the bidirectional
        // binding below (the binding's programmatic "9000" set passes). AppState.parseTrackerPort
        // remains the defensive backstop for a blank field or a stale/hand-edited preference.
        UnaryOperator<TextFormatter.Change> validPort = c -> {
            String t = c.getControlNewText();
            if (t.isEmpty()) return c;                 // allow clearing the field
            if (!t.matches("\\d{1,5}")) return null;   // digits only, at most 5
            return Integer.parseInt(t) <= 65535 ? c : null; // never exceed the max TCP port
        };
        portField.setTextFormatter(new TextFormatter<>(validPort));
        portField.textProperty().bindBidirectional(state.trackerPort);

        fields.getChildren().addAll(hostField, portField);
        card.getChildren().addAll(heading, fields);
        return card;
    }

    private Node buildFolderCard(AppState state) {
        VBox card = new VBox(12);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle("-fx-background-color: #161F28; -fx-background-radius: 10; -fx-border-color: #26313C; -fx-border-radius: 10; -fx-border-width: 1;");

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        TextField folderField = new TextField(state.sharedFolderPath.get());
        folderField.setFont(Font.font("System", 14));
        folderField.setPrefHeight(40);
        HBox.setHgrow(folderField, Priority.ALWAYS);
        folderField.setStyle(fieldStyle());
        folderField.textProperty().bindBidirectional(state.sharedFolderPath);

        Button browseBtn = new Button("Browse...");
        browseBtn.setPrefHeight(40);
        browseBtn.setFont(Font.font("System", 13));
        browseBtn.setStyle("-fx-background-color: #26313C; -fx-text-fill: #CBD5DF; -fx-background-radius: 8; -fx-cursor: hand;");
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose Shared Folder");
            File chosen = dc.showDialog(new Stage());
            if (chosen != null) {
                state.sharedFolderPath.set(chosen.getAbsolutePath());
                folderField.setText(chosen.getAbsolutePath());
                state.refreshSharedFiles();
            }
        });

        row.getChildren().addAll(folderField, browseBtn);
        card.getChildren().add(row);
        return card;
    }

    private Node buildProfileCard(AppState state) {
        VBox card = new VBox(12);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle("-fx-background-color: #161F28; -fx-background-radius: 10; -fx-border-color: #26313C; -fx-border-radius: 10; -fx-border-width: 1;");

        Label label = new Label("Your display name (visible to others on the network):");
        label.setFont(Font.font("System", 14));
        label.setTextFill(Color.web("#B3C0CC"));
        TextField nameField = new TextField(state.myDisplayName.get());
        nameField.setPromptText("Your name");
        nameField.setPrefHeight(40);
        nameField.setFont(Font.font("System", 14));
        nameField.setStyle(fieldStyle());
        nameField.textProperty().bindBidirectional(state.myDisplayName);

        card.getChildren().addAll(label, nameField);
        return card;
    }

    private Node buildSecurityCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(16, 20, 16, 20));
        card.setStyle("-fx-background-color: #0E1F16; -fx-background-radius: 10; -fx-border-color: #2C5A3F; -fx-border-radius: 10; -fx-border-width: 1;");

        Label heading = new Label("Encryption: Always On");
        heading.setFont(Font.font("System", FontWeight.BOLD, 14));
        heading.setTextFill(Color.web("#46C46A"));
        HBox headingRow = new HBox(9, Icons.icon(Icons.LOCK, 15, Color.web("#46C46A"), 2.0), heading);
        headingRow.setAlignment(Pos.CENTER_LEFT);

        Label body = new Label(
                "All file transfers between peers are encrypted using TLS 1.3 " +
                "with a self-signed certificate generated at startup. " +
                "File integrity is verified with SHA-256 checksums on every chunk. " +
                "You don't need to do anything - it's automatic.");
        body.setTextFill(Color.web("#7FB892"));
        body.setFont(Font.font("System", 13));
        body.setWrapText(true);

        card.getChildren().addAll(headingRow, body);
        return card;
    }

    private Node buildSaveButton(AppState state) {
        Button saveBtn = new Button("Save Settings");
        saveBtn.setFont(Font.font("System", FontWeight.BOLD, 15));
        saveBtn.setPrefHeight(46);
        saveBtn.setPrefWidth(200);
        saveBtn.setStyle(SAVE_IDLE_STYLE);

        // DT.5: a colored status line that reports the REAL save outcome. The button used to morph
        // into a green "Saved!" the instant it was clicked — before the background reconnect() had
        // even run, so it claimed success even when the tracker was unreachable or the port unparsable.
        Label status = new Label();
        status.setFont(Font.font("System", FontWeight.BOLD, 13));
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);
        HBox.setHgrow(status, Priority.ALWAYS);

        saveBtn.setOnAction(e -> {
            state.savePrefs();
            // Show progress while the (blocking, 5s-timeout) reconnect runs on a worker, then reflect
            // the ACTUAL result. An unreachable tracker keeps the button in "Saving..." until it really
            // fails, rather than instantly lying that it saved.
            saveBtn.setDisable(true);
            saveBtn.setText("Saving...");
            saveBtn.setGraphic(null);
            saveBtn.setStyle(SAVE_BUSY_STYLE);
            status.setVisible(false);
            status.setManaged(false);
            Thread worker = new Thread(() -> {
                AppState.ReconnectResult result = state.reconnect();
                javafx.application.Platform.runLater(() -> showSaveResult(saveBtn, status, result));
            }, "settings-save");
            worker.setDaemon(true);
            worker.start();
        });

        HBox box = new HBox(14, saveBtn, status);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Restores the Save button and shows an honest, color-coded outcome of the last save (DT.5). */
    private void showSaveResult(Button saveBtn, Label status, AppState.ReconnectResult result) {
        saveBtn.setDisable(false);
        saveBtn.setText("Save Settings");
        saveBtn.setGraphic(null);
        saveBtn.setStyle(SAVE_IDLE_STYLE);

        String msg;
        String iconPath;
        Color color;
        switch (result) {
            case CONNECTED -> {
                msg = "Connected to tracker.";
                iconPath = Icons.CHECK;
                color = Color.web("#46C46A");
            }
            case UNREACHABLE -> {
                msg = "Settings saved, but the tracker couldn't be reached.";
                iconPath = Icons.ALERT;
                color = Color.web("#E0A458");
            }
            default -> { // AUTO_DISCOVER: settings persisted; auto-discovery owns the connection
                msg = "Settings saved.";
                iconPath = Icons.CHECK;
                color = Color.web("#46C46A");
            }
        }
        status.setText(msg);
        status.setTextFill(color);
        status.setGraphic(Icons.icon(iconPath, 14, color, 2.2));
        status.setGraphicTextGap(7);
        status.setVisible(true);
        status.setManaged(true);
    }

    private String fieldStyle() {
        return "-fx-background-color: #1D2730; -fx-text-fill: white; " +
               "-fx-border-color: #36434F; -fx-border-radius: 8; -fx-background-radius: 8; " +
               "-fx-prompt-text-fill: #5E6B77; -fx-padding: 0 12 0 12;";
    }
}
