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
        root.setStyle("-fx-background-color: #0f0f13;");

        // ── Header ───────────────────────────────────────────────────────────
        HBox header = new HBox();
        header.setPadding(new Insets(24, 32, 16, 32));
        header.setStyle("-fx-background-color: #13131a;");
        Label title = new Label("Downloads");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setTextFill(Color.WHITE);
        Label subtitle = new Label("   —  Files you've downloaded or are currently downloading");
        subtitle.setFont(Font.font("System", 14));
        subtitle.setTextFill(Color.web("#555877"));
        subtitle.setAlignment(Pos.BOTTOM_LEFT);
        header.getChildren().addAll(title, subtitle);

        // ── List ─────────────────────────────────────────────────────────────
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0f0f13; -fx-background-color: #0f0f13; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox list = new VBox(12);
        list.setPadding(new Insets(20, 32, 20, 32));
        list.setStyle("-fx-background-color: #0f0f13;");

        AppState state = AppState.get();

        // Empty state
        Label empty = new Label("No downloads yet.\nGo to 🔍 Search to find and download files from the network.");
        empty.setTextFill(Color.web("#444460"));
        empty.setFont(Font.font("System", 15));
        empty.setTextAlignment(TextAlignment.CENTER);
        empty.setWrapText(true);
        empty.setVisible(state.downloads.isEmpty());
        list.getChildren().add(empty);

        // Bind to observable list
        state.downloads.addListener((javafx.collections.ListChangeListener<DownloadTask>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (DownloadTask task : change.getAddedSubList()) {
                        Node card = buildDownloadCard(task);
                        Platform.runLater(() -> {
                            empty.setVisible(false);
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

    private Node buildDownloadCard(DownloadTask task) {
        VBox card = new VBox(12);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setStyle(
                "-fx-background-color: #16161f; -fx-background-radius: 12; " +
                "-fx-border-color: #2a2a3a; -fx-border-radius: 12; -fx-border-width: 1;");

        // File name row
        HBox nameRow = new HBox(10);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label(fileIcon(task.filename));
        icon.setFont(Font.font("System", 26));
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
        bar.setStyle("-fx-accent: #7c6ef7;");

        // Detail row
        HBox detailRow = new HBox(16);
        detailRow.setAlignment(Pos.CENTER_LEFT);
        Label progressText = new Label(task.getProgressText());
        progressText.setTextFill(Color.web("#6b7080"));
        progressText.setFont(Font.font("System", 12));
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setFont(Font.font("System", 12));
        cancelBtn.setStyle(
                "-fx-background-color: #2a1a1a; -fx-text-fill: #f44336; " +
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

        // Live updates
        task.onProgressUpdate = t -> Platform.runLater(() -> {
            bar.setProgress(t.progress);
            progressText.setText(t.getProgressText());
            statusLabel.setText(stateText(t.state));
            statusLabel.setTextFill(stateColor(t.state));
        });
        task.onComplete = t -> Platform.runLater(() -> {
            bar.setProgress(1.0);
            bar.setStyle("-fx-accent: #4caf50;");
            statusLabel.setText("✓ Complete");
            statusLabel.setTextFill(Color.web("#4caf50"));
            progressText.setText("Download complete — saved to your shared folder");
            cancelBtn.setVisible(false);
            card.setStyle(
                    "-fx-background-color: #0f1a0f; -fx-background-radius: 12; " +
                    "-fx-border-color: #2a5a2a; -fx-border-radius: 12; -fx-border-width: 1;");
        });
        task.onError = t -> Platform.runLater(() -> {
            bar.setStyle("-fx-accent: #f44336;");
            statusLabel.setText("✗ Failed");
            statusLabel.setTextFill(Color.web("#f44336"));
            progressText.setText(t.errorMessage != null ? t.errorMessage : "Unknown error");
            cancelBtn.setVisible(false);
            card.setStyle(
                    "-fx-background-color: #1a0f0f; -fx-background-radius: 12; " +
                    "-fx-border-color: #5a2a2a; -fx-border-radius: 12; -fx-border-width: 1;");
        });

        return card;
    }

    private String stateText(DownloadManager.DownloadState s) {
        return switch (s) {
            case QUEUED -> "Queued";
            case CONNECTING -> "Connecting...";
            case DOWNLOADING -> "Downloading";
            case VERIFYING -> "Verifying...";
            case COMPLETE -> "✓ Complete";
            case FAILED -> "✗ Failed";
            case PAUSED -> "Paused";
        };
    }

    private Color stateColor(DownloadManager.DownloadState s) {
        return switch (s) {
            case QUEUED -> Color.web("#888");
            case CONNECTING, DOWNLOADING -> Color.web("#7c6ef7");
            case VERIFYING -> Color.web("#ffa726");
            case COMPLETE -> Color.web("#4caf50");
            case FAILED -> Color.web("#f44336");
            case PAUSED -> Color.web("#ffa726");
        };
    }

    private String fileIcon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "📄";
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv")) return "🎬";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav")) return "🎵";
        if (lower.endsWith(".jpg") || lower.endsWith(".png")) return "🖼";
        if (lower.endsWith(".zip") || lower.endsWith(".tar")) return "🗜";
        return "📁";
    }
}
