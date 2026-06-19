package com.p2p.desktop.ui;

import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager;
import com.p2p.desktop.AppState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;

import java.io.File;
import java.util.List;

public class SearchView {

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: #0f0f13;");

        // ── Hero Search Bar ──────────────────────────────────────────────────
        VBox heroBox = new VBox(16);
        heroBox.setAlignment(Pos.CENTER);
        heroBox.setPadding(new Insets(48, 40, 32, 40));
        heroBox.setStyle("-fx-background-color: #13131a;");

        Label heading = new Label("Find Files on Your Network");
        heading.setFont(Font.font("System", FontWeight.BOLD, 24));
        heading.setTextFill(Color.WHITE);

        Label sub = new Label("Search for files shared by people on the same Wi-Fi or network as you.");
        sub.setFont(Font.font("System", 14));
        sub.setTextFill(Color.web("#6b7080"));
        sub.setWrapText(true);
        sub.setTextAlignment(TextAlignment.CENTER);

        HBox searchBar = new HBox(10);
        searchBar.setAlignment(Pos.CENTER);
        searchBar.setMaxWidth(640);

        TextField searchField = new TextField();
        searchField.setPromptText("Type a filename, e.g. \"lecture_notes.pdf\"  ...");
        searchField.setFont(Font.font("System", 15));
        searchField.setPrefHeight(48);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setStyle(
                "-fx-background-color: #1e1e2e; -fx-text-fill: white; " +
                "-fx-border-color: #3a3a5a; -fx-border-radius: 8; -fx-background-radius: 8; " +
                "-fx-prompt-text-fill: #555877; -fx-padding: 0 16 0 16;");

        Button searchBtn = new Button("Search");
        searchBtn.setPrefHeight(48);
        searchBtn.setPrefWidth(110);
        searchBtn.setFont(Font.font("System", FontWeight.BOLD, 14));
        searchBtn.setStyle(
                "-fx-background-color: #7c6ef7; -fx-text-fill: white; " +
                "-fx-background-radius: 8; -fx-cursor: hand;");

        searchBar.getChildren().addAll(searchField, searchBtn);
        heroBox.getChildren().addAll(heading, sub, searchBar);

        // ── Results Area ────────────────────────────────────────────────────
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #0f0f13; -fx-background-color: #0f0f13; -fx-border-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox resultsBox = new VBox(12);
        resultsBox.setPadding(new Insets(24, 40, 24, 40));
        resultsBox.setStyle("-fx-background-color: #0f0f13;");

        Label placeholder = new Label("🔍  Search for a file above to see results here.");
        placeholder.setTextFill(Color.web("#444460"));
        placeholder.setFont(Font.font("System", 15));
        resultsBox.getChildren().add(placeholder);
        scrollPane.setContent(resultsBox);

        // ── Search Action ────────────────────────────────────────────────────
        Runnable doSearch = () -> {
            String query = searchField.getText().trim();
            if (query.isEmpty()) return;

            resultsBox.getChildren().clear();
            Label searching = new Label("Searching the network for \"" + query + "\"...");
            searching.setTextFill(Color.web("#7c6ef7"));
            searching.setFont(Font.font("System", 14));
            resultsBox.getChildren().add(searching);

            AppState state = AppState.get();
            if (!state.isConnected) {
                resultsBox.getChildren().clear();
                resultsBox.getChildren().add(buildErrorCard(
                        "Not Connected to Network",
                        "Go to ⚙ Settings and enter your tracker address, or wait for auto-discovery."));
                return;
            }

            new Thread(() -> {
                List<PeerInfo> peers = state.trackerClient.query(query);
                Platform.runLater(() -> {
                    resultsBox.getChildren().clear();
                    if (peers.isEmpty()) {
                        resultsBox.getChildren().add(buildEmptyResult(query));
                        return;
                    }
                    Label countLabel = new Label("Found \"" + query + "\" on " + peers.size() + " peer(s):");
                    countLabel.setTextFill(Color.web("#a0a0c0"));
                    countLabel.setFont(Font.font("System", 13));
                    resultsBox.getChildren().add(countLabel);

                    for (PeerInfo peer : peers) {
                        FileInfo fileInfo = peer.files.stream()
                                .filter(f -> f.name.equalsIgnoreCase(query))
                                .findFirst().orElse(null);
                        if (fileInfo != null) {
                            resultsBox.getChildren().add(buildResultCard(fileInfo, peers));
                            break; // Show one card per file, aggregate peers
                        }
                    }
                });
            }).start();
        };

        searchBtn.setOnAction(e -> doSearch.run());
        searchField.setOnAction(e -> doSearch.run());

        root.getChildren().addAll(heroBox, scrollPane);
        return root;
    }

    private Node buildResultCard(FileInfo file, List<PeerInfo> peers) {
        HBox card = new HBox(16);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle(
                "-fx-background-color: #16161f; -fx-background-radius: 12; " +
                "-fx-border-color: #2a2a3a; -fx-border-radius: 12; -fx-border-width: 1;");

        Label icon = new Label(fileIcon(file.name));
        icon.setFont(Font.font("System", 32));

        VBox meta = new VBox(4);
        HBox.setHgrow(meta, Priority.ALWAYS);

        Label nameLabel = new Label(file.name);
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        nameLabel.setTextFill(Color.WHITE);

        String sizeStr = formatSize(file.size);
        Label details = new Label(sizeStr + "  ·  " + peers.size() + " source(s)  ·  " + file.totalChunks + " chunks");
        details.setTextFill(Color.web("#6b7080"));
        details.setFont(Font.font("System", 13));

        meta.getChildren().addAll(nameLabel, details);

        Button downloadBtn = new Button("⬇  Download");
        downloadBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        downloadBtn.setStyle(
                "-fx-background-color: #7c6ef7; -fx-text-fill: white; " +
                "-fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 10 20 10 20;");

        downloadBtn.setOnAction(e -> startDownload(file, peers, downloadBtn));
        card.getChildren().addAll(icon, meta, downloadBtn);
        return card;
    }

    private void startDownload(FileInfo file, List<PeerInfo> peers, Button btn) {
        AppState state = AppState.get();
        File outFile = new File(state.getSharedFolder(), file.name);

        boolean alreadyDownloading = state.downloads.stream()
                .anyMatch(t -> t.filename.equals(file.name) &&
                        t.state != DownloadManager.DownloadState.COMPLETE &&
                        t.state != DownloadManager.DownloadState.FAILED);
        if (alreadyDownloading) {
            btn.setText("⬇  Already queued");
            return;
        }

        DownloadManager.DownloadTask task = new DownloadManager.DownloadTask(
                file.name, file.size, outFile, peers);

        task.onProgressUpdate = t -> Platform.runLater(() -> btn.setText("⬇  " + t.getProgressText()));
        task.onComplete = t -> Platform.runLater(() -> {
            btn.setText("✓  Done!");
            btn.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-background-radius: 8;");
            state.refreshSharedFiles();
        });
        task.onError = t -> Platform.runLater(() -> {
            btn.setText("✗  Failed — click to retry");
            btn.setStyle("-fx-background-color: #c62828; -fx-text-fill: white; -fx-background-radius: 8;");
        });

        state.downloads.add(task);
        state.downloadManager.download(task);
        btn.setText("⬇  Starting...");
    }

    private Node buildEmptyResult(String query) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60, 0, 0, 0));
        Label icon = new Label("🤷");
        icon.setFont(Font.font("System", 40));
        Label msg = new Label("No one on the network is sharing \"" + query + "\" right now.");
        msg.setTextFill(Color.web("#555877"));
        msg.setFont(Font.font("System", 15));
        Label hint = new Label("Ask someone to share it and try again.");
        hint.setTextFill(Color.web("#3a3a5a"));
        hint.setFont(Font.font("System", 13));
        box.getChildren().addAll(icon, msg, hint);
        return box;
    }

    private Node buildErrorCard(String title, String body) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color: #1e0e0e; -fx-background-radius: 12; -fx-border-color: #f44336; -fx-border-radius: 12; -fx-border-width: 1;");
        Label t = new Label("⚠  " + title);
        t.setFont(Font.font("System", FontWeight.BOLD, 15));
        t.setTextFill(Color.web("#f44336"));
        Label b = new Label(body);
        b.setTextFill(Color.web("#cc8888"));
        b.setFont(Font.font("System", 13));
        b.setWrapText(true);
        box.getChildren().addAll(t, b);
        return box;
    }

    private String fileIcon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "📄";
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")) return "🎬";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac")) return "🎵";
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg")) return "🖼";
        if (lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz")) return "🗜";
        if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js")) return "💻";
        return "📁";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
