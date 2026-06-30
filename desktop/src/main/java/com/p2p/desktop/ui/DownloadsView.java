package com.p2p.desktop.ui;

import com.p2p.core.transfer.DownloadManager;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import com.p2p.desktop.AppState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;

public class DownloadsView {

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: #0E1419;");

        // ── Header ───────────────────────────────────────────────────────────
        HBox header = new HBox();
        header.setPadding(new Insets(24, 32, 16, 32));
        header.setStyle("-fx-background-color: #121A21;");
        Label title = new Label("Downloads");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setTextFill(Color.WHITE);
        Label subtitle = new Label("   ·  Files you've downloaded or are currently downloading");
        subtitle.setFont(Font.font("System", 14));
        subtitle.setTextFill(Color.web("#5E6B77"));
        subtitle.setAlignment(Pos.BOTTOM_LEFT);
        header.getChildren().addAll(title, subtitle);

        // ── List ─────────────────────────────────────────────────────────────
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0E1419; -fx-background-color: #0E1419; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox list = new VBox(12);
        list.setPadding(new Insets(20, 32, 20, 32));
        list.setStyle("-fx-background-color: #0E1419;");

        AppState state = AppState.get();

        // Empty state — the same centered icon + message + hint treatment the
        // Search view uses for its empty results, so all four views are consistent.
        Node empty = buildEmptyState();
        boolean isEmpty = state.downloads.isEmpty();
        empty.setVisible(isEmpty);
        empty.setManaged(isEmpty);
        list.getChildren().add(empty);

        // Bind to observable list
        state.downloads.addListener((javafx.collections.ListChangeListener<DownloadTask>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (DownloadTask task : change.getAddedSubList()) {
                        Node card = buildDownloadCard(task);
                        Platform.runLater(() -> {
                            empty.setVisible(false);
                            empty.setManaged(false);
                            list.getChildren().add(card);
                        });
                    }
                }
            }
        });

        scroll.setContent(list);
        root.getChildren().addAll(header, scroll);
        return root;
    }

    private Node buildEmptyState() {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60, 0, 0, 0));
        Node icon = Icons.icon(Icons.INBOX, 44, Color.web("#36434F"), 1.6);
        Label msg = new Label("No downloads yet.");
        msg.setTextFill(Color.web("#5E6B77"));
        msg.setFont(Font.font("System", 15));
        Label hint = new Label("Go to Search to find and download files from the network.");
        hint.setTextFill(Color.web("#36434F"));
        hint.setFont(Font.font("System", 13));
        box.getChildren().addAll(icon, msg, hint);
        return box;
    }

    private Node buildDownloadCard(DownloadTask task) {
        VBox card = new VBox(12);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setStyle(
                "-fx-background-color: #161F28; -fx-background-radius: 12; " +
                "-fx-border-color: #26313C; -fx-border-radius: 12; -fx-border-width: 1;");

        // File name row
        HBox nameRow = new HBox(10);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Node icon = Icons.fileIcon(task.filename, 24, Color.web("#9AA8B5"));
        Label name = new Label(task.filename);
        name.setFont(Font.font("System", FontWeight.BOLD, 16));
        name.setTextFill(Color.WHITE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusLabel = new Label(stateText(task.state));
        statusLabel.setFont(Font.font("System", 13));
        statusLabel.setTextFill(stateColor(task.state));

        nameRow.getChildren().addAll(icon, name, spacer, statusLabel);

        // Progress bar
        ProgressBar bar = new ProgressBar(task.progress);
        bar.setPrefWidth(Double.MAX_VALUE);
        bar.setPrefHeight(10);
        bar.setStyle("-fx-accent: #0E8C77;");

        // Detail row
        HBox detailRow = new HBox(16);
        detailRow.setAlignment(Pos.CENTER_LEFT);
        Label progressText = new Label(task.getProgressText());
        progressText.setTextFill(Color.web("#93A1AE"));
        progressText.setFont(Font.font("System", 12));
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setFont(Font.font("System", 12));
        cancelBtn.setStyle(
                "-fx-background-color: #2A1414; -fx-text-fill: #E5564E; " +
                "-fx-background-radius: 6; -fx-cursor: hand;");
        cancelBtn.setVisible(task.state == DownloadManager.DownloadState.DOWNLOADING ||
                task.state == DownloadManager.DownloadState.QUEUED);
        cancelBtn.setOnAction(e -> {
            AppState.get().downloadManager.cancel(task.filename);
            statusLabel.setText("Cancelled");
            statusLabel.setTextFill(Color.web("#888"));
            cancelBtn.setVisible(false);
        });

        detailRow.getChildren().addAll(progressText, spacer2, cancelBtn);
        card.getChildren().addAll(nameRow, bar, detailRow);

        // Live updates. DT.3: register a listener rather than assigning task.onProgressUpdate/etc.,
        // so this card and the Search button (which also observes this task) are BOTH notified instead
        // of the later assignment clobbering the earlier one. The card lives in the Downloads list for
        // the task's lifetime, so this listener intentionally stays registered (no self-removal).
        task.addListener(new DownloadManager.DownloadListener() {
            @Override public void onProgress(DownloadTask t) {
                Platform.runLater(() -> {
                    bar.setProgress(t.progress);
                    progressText.setText(t.getProgressText());
                    statusLabel.setText(stateText(t.state));
                    statusLabel.setTextFill(stateColor(t.state));
                });
            }
            @Override public void onComplete(DownloadTask t) {
                Platform.runLater(() -> {
                    bar.setProgress(1.0);
                    bar.setStyle("-fx-accent: #46C46A;");
                    statusLabel.setText("Complete");
                    statusLabel.setTextFill(Color.web("#46C46A"));
                    progressText.setText("Download complete - click to open");
                    cancelBtn.setVisible(false);
                    card.setCursor(javafx.scene.Cursor.HAND);
                    card.setStyle(
                            "-fx-background-color: #0E1F16; -fx-background-radius: 12; " +
                            "-fx-border-color: #2C5A3F; -fx-border-radius: 12; -fx-border-width: 1;");
                });
            }
            @Override public void onError(DownloadTask t) {
                Platform.runLater(() -> {
                    bar.setStyle("-fx-accent: #E5564E;");
                    statusLabel.setText("Failed");
                    statusLabel.setTextFill(Color.web("#E5564E"));
                    progressText.setText(t.errorMessage != null ? t.errorMessage : "Unknown error");
                    cancelBtn.setVisible(false);
                    card.setStyle(
                            "-fx-background-color: #1F1212; -fx-background-radius: 12; " +
                            "-fx-border-color: #5A3030; -fx-border-radius: 12; -fx-border-width: 1;");
                });
            }
        });

        // Click a completed card to open the file (falls back to the shared folder).
        if (task.state == DownloadManager.DownloadState.COMPLETE) card.setCursor(javafx.scene.Cursor.HAND);
        card.setOnMouseClicked(e -> openDownload(task));

        return card;
    }

    /** Open a completed download with the OS default app; fall back to the shared folder. */
    private void openDownload(DownloadTask task) {
        if (task.state != DownloadManager.DownloadState.COMPLETE) return;
        java.io.File file = new java.io.File(AppState.get().getSharedFolder(), task.filename);
        java.io.File folder = AppState.get().getSharedFolder();
        new Thread(() -> {
            try {
                if (!java.awt.Desktop.isDesktopSupported()) return;
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (file.exists()) desktop.open(file);
                else desktop.open(folder);
            } catch (Exception ex) {
                try { java.awt.Desktop.getDesktop().open(folder); } catch (Exception ignored) {}
            }
        }, "open-download").start();
    }

    private String stateText(DownloadManager.DownloadState s) {
        return switch (s) {
            case QUEUED -> "Queued";
            case CONNECTING -> "Connecting...";
            case DOWNLOADING -> "Downloading";
            case VERIFYING -> "Verifying...";
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
            case PAUSED -> "Paused";
        };
    }

    private Color stateColor(DownloadManager.DownloadState s) {
        return switch (s) {
            case QUEUED -> Color.web("#888");
            case CONNECTING, DOWNLOADING -> Color.web("#0E8C77");
            case VERIFYING -> Color.web("#E0A33A");
            case COMPLETE -> Color.web("#46C46A");
            case FAILED -> Color.web("#E5564E");
            case PAUSED -> Color.web("#E0A33A");
        };
    }

}
