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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SearchView {

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: #0E1419;");

        // ── Hero Search Bar ──────────────────────────────────────────────────
        VBox heroBox = new VBox(16);
        heroBox.setAlignment(Pos.CENTER);
        heroBox.setPadding(new Insets(48, 40, 32, 40));
        heroBox.setStyle("-fx-background-color: #121A21;");

        Label heading = new Label("Find Files on Your Network");
        heading.setFont(Font.font("System", FontWeight.BOLD, 24));
        heading.setTextFill(Color.WHITE);

        Label sub = new Label("Search for files shared by people on the same Wi-Fi or network as you.");
        sub.setFont(Font.font("System", 14));
        sub.setTextFill(Color.web("#93A1AE"));
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
                "-fx-background-color: #1D2730; -fx-text-fill: white; " +
                "-fx-border-color: #36434F; -fx-border-radius: 8; -fx-background-radius: 8; " +
                "-fx-prompt-text-fill: #5E6B77; -fx-padding: 0 16 0 16;");

        Button searchBtn = new Button("Search");
        searchBtn.setPrefHeight(48);
        searchBtn.setPrefWidth(110);
        searchBtn.setFont(Font.font("System", FontWeight.BOLD, 14));
        searchBtn.setStyle(
                "-fx-background-color: #0E8C77; -fx-text-fill: white; " +
                "-fx-background-radius: 8; -fx-cursor: hand;");

        Button browseBtn = new Button("Browse All");
        browseBtn.setPrefHeight(48);
        browseBtn.setPrefWidth(120);
        browseBtn.setFont(Font.font("System", FontWeight.BOLD, 14));
        browseBtn.setStyle(
                "-fx-background-color: #26313C; -fx-text-fill: #CBD5DF; " +
                "-fx-background-radius: 8; -fx-cursor: hand;");

        searchBar.getChildren().addAll(searchField, searchBtn, browseBtn);
        heroBox.getChildren().addAll(heading, sub, searchBar);

        // ── Results Area ────────────────────────────────────────────────────
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #0E1419; -fx-background-color: #0E1419; -fx-border-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox resultsBox = new VBox(12);
        resultsBox.setPadding(new Insets(24, 40, 24, 40));
        resultsBox.setStyle("-fx-background-color: #0E1419;");

        Label placeholder = new Label("🔍  Search for a file above to see results here.");
        placeholder.setTextFill(Color.web("#4A5662"));
        placeholder.setFont(Font.font("System", 15));
        resultsBox.getChildren().add(placeholder);
        scrollPane.setContent(resultsBox);

        // ── Search Action ────────────────────────────────────────────────────
        Runnable doSearch = () -> {
            String query = searchField.getText().trim();
            if (query.isEmpty()) return;

            resultsBox.getChildren().clear();
            Label searching = new Label("Searching the network for \"" + query + "\"...");
            searching.setTextFill(Color.web("#0E8C77"));
            searching.setFont(Font.font("System", 14));
            resultsBox.getChildren().add(searching);

            AppState state = AppState.get();
            if (!state.isConnected.get()) {
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
                    countLabel.setTextFill(Color.web("#B3C0CC"));
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

        // ── Browse Action ────────────────────────────────────────────────────
        Runnable doBrowse = () -> {
            AppState state = AppState.get();
            if (!state.isConnected.get()) {
                resultsBox.getChildren().clear();
                resultsBox.getChildren().add(buildErrorCard(
                        "Not Connected to Network",
                        "Go to ⚙ Settings and enter your tracker address, or wait for auto-discovery."));
                return;
            }

            resultsBox.getChildren().clear();
            Label loading = new Label("Loading all files on the network...");
            loading.setTextFill(Color.web("#0E8C77"));
            loading.setFont(Font.font("System", 14));
            resultsBox.getChildren().add(loading);

            new Thread(() -> {
                List<PeerInfo> peers = state.trackerClient.browseAll();
                // Aggregate unique files (by name) -> list of peers that have them
                Map<String, FileInfo> filesByName = new LinkedHashMap<>();
                Map<String, List<PeerInfo>> peersByFile = new LinkedHashMap<>();
                for (PeerInfo peer : peers) {
                    if (peer.files == null) continue;
                    for (FileInfo f : peer.files) {
                        filesByName.putIfAbsent(f.name, f);
                        peersByFile.computeIfAbsent(f.name, k -> new ArrayList<>()).add(peer);
                    }
                }
                Platform.runLater(() -> {
                    resultsBox.getChildren().clear();
                    if (filesByName.isEmpty()) {
                        resultsBox.getChildren().add(buildEmptyBrowse());
                        return;
                    }
                    Label countLabel = new Label(filesByName.size() + " file(s) available on the network:");
                    countLabel.setTextFill(Color.web("#B3C0CC"));
                    countLabel.setFont(Font.font("System", 13));
                    resultsBox.getChildren().add(countLabel);
                    for (Map.Entry<String, FileInfo> entry : filesByName.entrySet()) {
                        resultsBox.getChildren().add(
                                buildResultCard(entry.getValue(), peersByFile.get(entry.getKey())));
                    }
                });
            }).start();
        };

        searchBtn.setOnAction(e -> doSearch.run());
        searchField.setOnAction(e -> doSearch.run());
        browseBtn.setOnAction(e -> doBrowse.run());

        root.getChildren().addAll(heroBox, scrollPane);
        return root;
    }

    private Node buildResultCard(FileInfo file, List<PeerInfo> peers) {
        HBox card = new HBox(16);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle(
                "-fx-background-color: #161F28; -fx-background-radius: 12; " +
                "-fx-border-color: #26313C; -fx-border-radius: 12; -fx-border-width: 1;");

        Label icon = new Label(fileIcon(file.name));
        icon.setFont(Font.font("System", 32));

        VBox meta = new VBox(4);
        HBox.setHgrow(meta, Priority.ALWAYS);

        Label nameLabel = new Label(file.name);
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        nameLabel.setTextFill(Color.WHITE);

        String sizeStr = formatSize(file.size);
        Label details = new Label(sizeStr + "  ·  " + peers.size() + " source(s)  ·  " + file.totalChunks + " chunks");
        details.setTextFill(Color.web("#93A1AE"));
        details.setFont(Font.font("System", 13));

        meta.getChildren().addAll(nameLabel, details);

        Button downloadBtn = new Button("⬇  Download");
        downloadBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        downloadBtn.setStyle(
                "-fx-background-color: #0E8C77; -fx-text-fill: white; " +
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
            btn.setStyle("-fx-background-color: #2C7A45; -fx-text-fill: white; -fx-background-radius: 8;");
            state.refreshSharedFiles();
        });
        task.onError = t -> Platform.runLater(() -> {
            btn.setText("✗  Failed — click to retry");
            btn.setStyle("-fx-background-color: #B23B36; -fx-text-fill: white; -fx-background-radius: 8;");
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
        msg.setTextFill(Color.web("#5E6B77"));
        msg.setFont(Font.font("System", 15));
        Label hint = new Label("Ask someone to share it and try again.");
        hint.setTextFill(Color.web("#36434F"));
        hint.setFont(Font.font("System", 13));
        box.getChildren().addAll(icon, msg, hint);
        return box;
    }

    private Node buildEmptyBrowse() {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60, 0, 0, 0));
        Label icon = new Label("📭");
        icon.setFont(Font.font("System", 40));
        Label msg = new Label("No files are being shared on the network right now.");
        msg.setTextFill(Color.web("#5E6B77"));
        msg.setFont(Font.font("System", 15));
        Label hint = new Label("Ask someone to add files to their shared folder.");
        hint.setTextFill(Color.web("#36434F"));
        hint.setFont(Font.font("System", 13));
        box.getChildren().addAll(icon, msg, hint);
        return box;
    }

    private Node buildErrorCard(String title, String body) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color: #1F1212; -fx-background-radius: 12; -fx-border-color: #E5564E; -fx-border-radius: 12; -fx-border-width: 1;");
        Label t = new Label("⚠  " + title);
        t.setFont(Font.font("System", FontWeight.BOLD, 15));
        t.setTextFill(Color.web("#E5564E"));
        Label b = new Label(body);
        b.setTextFill(Color.web("#D69B97"));
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
