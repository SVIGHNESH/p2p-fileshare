package com.p2p.desktop;

import com.p2p.desktop.ui.*;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class App extends Application {

    private Tab searchTab, downloadsTab, sharingTab, settingsTab;

    @Override
    public void start(Stage stage) {
        AppState.get().init();

        stage.setTitle("P2P Share");
        stage.setMinWidth(900);
        stage.setMinHeight(640);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0f0f13;");

        // ── Header ──────────────────────────────────────────────────────────
        root.setTop(buildHeader());

        // ── Tab Navigation ───────────────────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color: #0f0f13;");

        searchTab   = buildTab("🔍  Search",    new SearchView().build());
        downloadsTab= buildTab("⬇  Downloads", new DownloadsView().build());
        sharingTab  = buildTab("📤  My Files",  new SharingView().build());
        settingsTab = buildTab("⚙  Settings",  new SettingsView().build());

        tabs.getTabs().addAll(searchTab, downloadsTab, sharingTab, settingsTab);
        root.setCenter(tabs);

        // ── Status Bar ───────────────────────────────────────────────────────
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1024, 680);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private HBox buildHeader() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setStyle("-fx-background-color: #16161f; -fx-border-color: #2a2a3a; -fx-border-width: 0 0 1 0;");

        Label logo = new Label("⬡");
        logo.setFont(Font.font("System", FontWeight.BOLD, 28));
        logo.setTextFill(Color.web("#7c6ef7"));

        Label appName = new Label("P2P Share");
        appName.setFont(Font.font("System", FontWeight.BOLD, 20));
        appName.setTextFill(Color.WHITE);

        Label tagline = new Label("— Secure file sharing on your network");
        tagline.setFont(Font.font("System", 13));
        tagline.setTextFill(Color.web("#6b7080"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusDot = new Label(AppState.get().isConnected ? "● Connected" : "● Not connected");
        statusDot.setTextFill(AppState.get().isConnected ? Color.web("#4caf50") : Color.web("#f44336"));
        statusDot.setFont(Font.font("System", 13));

        header.getChildren().addAll(logo, appName, tagline, spacer, statusDot);
        return header;
    }

    private HBox buildStatusBar() {
        HBox bar = new HBox(20);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 20, 8, 20));
        bar.setStyle("-fx-background-color: #0d0d10; -fx-border-color: #1e1e2a; -fx-border-width: 1 0 0 0;");

        Label myIpLabel = new Label("Your IP: " + AppState.get().myIp);
        myIpLabel.setTextFill(Color.web("#555877"));
        myIpLabel.setFont(Font.font("System", 11));

        Label sharedLabel = new Label("Shared folder: " + AppState.get().sharedFolderPath.get());
        sharedLabel.setTextFill(Color.web("#555877"));
        sharedLabel.setFont(Font.font("System", 11));
        AppState.get().sharedFolderPath.addListener((o, oldV, newV) -> sharedLabel.setText("Shared folder: " + newV));

        Label encLabel = new Label("🔒 Encrypted");
        encLabel.setTextFill(Color.web("#4caf50"));
        encLabel.setFont(Font.font("System", 11));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(myIpLabel, sharedLabel, spacer, encLabel);
        return bar;
    }

    private Tab buildTab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setStyle("-fx-background-color: #16161f;");
        return tab;
    }
}
