package com.p2p.desktop.ui;

import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.desktop.AppState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class SharingView {

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: #0E1419;");

        // ── Header ────────────────────────────────────────────────────────────
        HBox header = new HBox(16);
        header.setPadding(new Insets(24, 32, 16, 32));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #121A21;");

        VBox titleBox = new VBox(4);
        Label title = new Label("My Shared Files");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setTextFill(Color.WHITE);
        Label subtitle = new Label("Files in your shared folder are visible to everyone on the network.");
        subtitle.setFont(Font.font("System", 13));
        subtitle.setTextFill(Color.web("#5E6B77"));
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addBtn = new Button("Add File");
        addBtn.setFont(Font.font("System", FontWeight.BOLD, 14));
        addBtn.setGraphic(Icons.icon(Icons.PLUS, 15, Color.WHITE, 2.2));
        addBtn.setGraphicTextGap(8);
        addBtn.setStyle(
                "-fx-background-color: #0E8C77; -fx-text-fill: white; " +
                "-fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 10 20 10 20;");
        addBtn.setOnAction(e -> addFile());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setFont(Font.font("System", 13));
        refreshBtn.setGraphic(Icons.icon(Icons.REFRESH, 14, Color.web("#B3C0CC"), 2.0));
        refreshBtn.setGraphicTextGap(7);
        refreshBtn.setStyle(
                "-fx-background-color: #1D2730; -fx-text-fill: #B3C0CC; " +
                "-fx-border-color: #36434F; -fx-border-radius: 8; " +
                "-fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 10 16 10 16;");
        refreshBtn.setOnAction(e -> AppState.get().refreshSharedFiles());

        header.getChildren().addAll(titleBox, spacer, refreshBtn, addBtn);

        // ── Shared Folder Banner ──────────────────────────────────────────────
        HBox folderBanner = new HBox(10);
        folderBanner.setAlignment(Pos.CENTER_LEFT);
        folderBanner.setPadding(new Insets(12, 32, 12, 32));
        folderBanner.setStyle("-fx-background-color: #101A20;");
        Node folderIcon = Icons.icon(Icons.FOLDER, 16, Color.web("#0E8C77"));
        Label folderLabel = new Label("Shared folder: " + AppState.get().sharedFolderPath.get());
        folderLabel.setTextFill(Color.web("#0E8C77"));
        folderLabel.setFont(Font.font("System", 13));
        AppState.get().sharedFolderPath.addListener((o, ov, nv) ->
                folderLabel.setText("Shared folder: " + nv));
        folderBanner.getChildren().addAll(folderIcon, folderLabel);

        // ── File Grid ────────────────────────────────────────────────────────
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0E1419; -fx-background-color: #0E1419; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox listBox = new VBox(10);
        listBox.setPadding(new Insets(20, 32, 20, 32));
        listBox.setStyle("-fx-background-color: #0E1419;");

        // Render initial files
        renderFiles(listBox);

        // Live refresh on list change
        AppState.get().sharedFiles.addListener((javafx.collections.ListChangeListener<FileInfo>) c -> {
            renderFiles(listBox);
        });

        scroll.setContent(listBox);
        root.getChildren().addAll(header, folderBanner, scroll);
        return root;
    }

    private void renderFiles(VBox listBox) {
        listBox.getChildren().clear();
        AppState state = AppState.get();
        if (state.sharedFiles.isEmpty()) {
            Label empty = new Label("You're not sharing any files yet.\nClick \"+ Add File\" to share a file with the network.");
            empty.setTextFill(Color.web("#4A5662"));
            empty.setFont(Font.font("System", 15));
            empty.setTextAlignment(TextAlignment.CENTER);
            empty.setWrapText(true);
            listBox.getChildren().add(empty);
            return;
        }
        for (FileInfo fi : state.sharedFiles) {
            listBox.getChildren().add(buildFileRow(fi));
        }
    }

    private Node buildFileRow(FileInfo fi) {
        HBox row = new HBox(16);
        row.setPadding(new Insets(16, 20, 16, 20));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle(
                "-fx-background-color: #161F28; -fx-background-radius: 10; " +
                "-fx-border-color: #26313C; -fx-border-radius: 10; -fx-border-width: 1;");

        Node icon = Icons.fileIcon(fi.name, 26, Color.web("#9AA8B5"));

        VBox meta = new VBox(3);
        HBox.setHgrow(meta, Priority.ALWAYS);
        Label nameLabel = new Label(fi.name);
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        nameLabel.setTextFill(Color.WHITE);
        String chunkStr = fi.totalChunks + (fi.totalChunks == 1 ? " chunk" : " chunks");
        Label details = new Label(formatSize(fi.size) + "  ·  " + chunkStr + "  ·  Shared");
        details.setTextFill(Color.web("#46C46A"));
        details.setFont(Font.font("System", 12));
        meta.getChildren().addAll(nameLabel, details);

        Button removeBtn = new Button("Remove");
        removeBtn.setFont(Font.font("System", 12));
        removeBtn.setStyle(
                "-fx-background-color: #2A1414; -fx-text-fill: #E5564E; " +
                "-fx-background-radius: 6; -fx-cursor: hand;");
        removeBtn.setOnAction(e -> {
            File f = new File(AppState.get().getSharedFolder(), fi.name);
            if (f.exists()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Remove File");
                confirm.setHeaderText("Remove \"" + fi.name + "\" from sharing?");
                confirm.setContentText("This will delete the file from your shared folder.");
                confirm.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        f.delete();
                        AppState.get().refreshSharedFiles();
                    }
                });
            }
        });

        row.getChildren().addAll(icon, meta, removeBtn);
        return row;
    }

    private void addFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a file to share");
        File selected = chooser.showOpenDialog(new Stage());
        if (selected == null) return;
        try {
            File dest = new File(AppState.get().getSharedFolder(), selected.getName());
            Files.copy(selected.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            AppState.get().refreshSharedFiles();
        } catch (Exception e) {
            Alert err = new Alert(Alert.AlertType.ERROR, "Could not copy file: " + e.getMessage());
            err.showAndWait();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
