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

public class SettingsView {

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
                buildSectionHeader("🌐  Network — Tracker Connection"),
                buildInfoBox(
                        "What is a Tracker?",
                        "The Tracker is a small program that keeps track of who has which files. " +
                        "One person in your group needs to run it (see the README). " +
                        "If auto-discovery is enabled, you don't need to type anything — " +
                        "the tracker will be found automatically when you're on the same Wi-Fi."),
                buildAutoDiscoveryCard(state),
                buildTrackerManualCard(state),

                buildSectionHeader("📂  Shared Folder"),
                buildInfoBox(
                        "Your shared folder",
                        "Files you place in this folder are visible to others on the network. " +
                        "Downloaded files are also saved here."),
                buildFolderCard(state),

                buildSectionHeader("👤  Your Profile"),
                buildProfileCard(state),

                buildSectionHeader("🔒  Security"),
                buildSecurityCard(),

                buildSaveButton(state),

                buildSectionHeader("ℹ️  About"),
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

    private Node buildSectionHeader(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, 16));
        label.setTextFill(Color.web("#B3C0CC"));
        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #26313C;");
        VBox box = new VBox(8, label, sep);
        return box;
    }

    private Node buildInfoBox(String title, String body) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(14, 18, 14, 18));
        box.setStyle(
                "-fx-background-color: #101A20; -fx-background-radius: 8; " +
                "-fx-border-color: #24414F; -fx-border-radius: 8; -fx-border-width: 1;");
        Label t = new Label("ℹ  " + title);
        t.setFont(Font.font("System", FontWeight.BOLD, 13));
        t.setTextFill(Color.web("#58A8FF"));
        Label b = new Label(body);
        b.setTextFill(Color.web("#93A1AE"));
        b.setFont(Font.font("System", 13));
        b.setWrapText(true);
        box.getChildren().addAll(t, b);
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

        TextField portField = new TextField(state.trackerPort.get());
        portField.setPromptText("Port");
        portField.setPrefWidth(90);
        portField.setPrefHeight(40);
        portField.setFont(Font.font("System", 14));
        portField.setStyle(fieldStyle());
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

        Label heading = new Label("🔒  Encryption: Always On");
        heading.setFont(Font.font("System", FontWeight.BOLD, 14));
        heading.setTextFill(Color.web("#46C46A"));

        Label body = new Label(
                "All file transfers between peers are encrypted using TLS 1.3 " +
                "with a self-signed certificate generated at startup. " +
                "File integrity is verified with SHA-256 checksums on every chunk. " +
                "You don't need to do anything — it's automatic.");
        body.setTextFill(Color.web("#7FB892"));
        body.setFont(Font.font("System", 13));
        body.setWrapText(true);

        card.getChildren().addAll(heading, body);
        return card;
    }

    private Node buildSaveButton(AppState state) {
        Button saveBtn = new Button("Save Settings");
        saveBtn.setFont(Font.font("System", FontWeight.BOLD, 15));
        saveBtn.setPrefHeight(46);
        saveBtn.setPrefWidth(200);
        saveBtn.setStyle(
                "-fx-background-color: #0E8C77; -fx-text-fill: white; " +
                "-fx-background-radius: 10; -fx-cursor: hand;");
        saveBtn.setOnAction(e -> {
            state.savePrefs();
            new Thread(() -> {
                state.reconnect();
            }).start();
            saveBtn.setText("✓ Saved!");
            saveBtn.setStyle("-fx-background-color: #46C46A; -fx-text-fill: white; -fx-background-radius: 10;");
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (Exception ignored) {}
                javafx.application.Platform.runLater(() -> {
                    saveBtn.setText("Save Settings");
                    saveBtn.setStyle("-fx-background-color: #0E8C77; -fx-text-fill: white; -fx-background-radius: 10;");
                });
            }).start();
        });

        HBox box = new HBox(saveBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private String fieldStyle() {
        return "-fx-background-color: #1D2730; -fx-text-fill: white; " +
               "-fx-border-color: #36434F; -fx-border-radius: 8; -fx-background-radius: 8; " +
               "-fx-prompt-text-fill: #5E6B77; -fx-padding: 0 12 0 12;";
    }
}
