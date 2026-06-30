package com.p2p.desktop;

import com.p2p.desktop.ui.*;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class App extends Application {

    // Chrome palette (kept consistent with the existing per-tab surfaces).
    private static final Color ACCENT     = Color.web("#0E8C77");
    private static final Color TEXT_MUTED  = Color.web("#93A1AE");
    private static final Color OK_GREEN    = Color.web("#46C46A");
    private static final Color ERR_RED     = Color.web("#E5564E");

    @Override
    public void start(Stage stage) {
        stage.setTitle("P2P Share");
        stage.setMinWidth(900);
        stage.setMinHeight(640);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0E1419;");

        // ── Header ──────────────────────────────────────────────────────────
        root.setTop(buildHeader());

        // ── Tab Navigation ───────────────────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color: #0E1419;");

        tabs.getTabs().addAll(
                buildTab("Search",    Icons.SEARCH,   new SearchView().build()),
                buildTab("Downloads", Icons.DOWNLOAD, new DownloadsView().build()),
                buildTab("My Files",  Icons.FOLDER,   new SharingView().build()),
                buildTab("Settings",  Icons.SETTINGS, new SettingsView().build()));
        root.setCenter(tabs);

        // ── Status Bar ───────────────────────────────────────────────────────
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1024, 680);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        // DT.1: paint the window first, then run the blocking startup off the FX
        // thread. init() returns immediately; state populates the UI as it lands.
        AppState.get().init();
    }

    /**
     * DT.6: JavaFX calls this on the FX thread when the last window closes — the documented place for
     * cleanup. Previously there was no stop() override, so teardown relied on JavaFX's implicit-exit
     * calling {@code System.exit()} to fire a Runtime shutdown hook (which itself never stopped the
     * auto-discovery listener). {@link AppState#shutdown()} now stops the peer server, downloads and
     * discovery explicitly, so the process no longer depends on that {@code System.exit} to exit
     * cleanly. The Runtime hook is kept for SIGTERM/Ctrl-C and is a no-op second pass (idempotent).
     */
    @Override
    public void stop() {
        AppState.get().shutdown();
    }

    private HBox buildHeader() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setStyle("-fx-background-color: #161F28; -fx-border-color: #26313C; -fx-border-width: 0 0 1 0;");

        Node logo = Icons.icon(Icons.SHARE, 24, ACCENT, 2.4);

        Label appName = new Label("P2P Share");
        appName.setFont(Font.font("System", FontWeight.BOLD, 20));
        appName.setTextFill(Color.WHITE);

        Region gap = new Region();
        gap.setMinWidth(4);

        Label tagline = new Label("Secure file sharing on your local network");
        tagline.setFont(Font.font("System", 13));
        tagline.setTextFill(TEXT_MUTED);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(logo, appName, gap, tagline, spacer, buildConnectionBadge());
        return header;
    }

    /** A status pill that tracks the live tracker connection state. */
    private Node buildConnectionBadge() {
        boolean connected = AppState.get().isConnected.get();

        Circle dot = new Circle(4, connected ? OK_GREEN : ERR_RED);
        Label label = new Label(connected ? "Connected" : "Not connected");
        label.setFont(Font.font("System", 12.5));
        label.setTextFill(connected ? OK_GREEN : ERR_RED);

        HBox badge = new HBox(7, dot, label);
        badge.setAlignment(Pos.CENTER);
        badge.setPadding(new Insets(5, 12, 5, 12));
        badge.setStyle("-fx-background-color: #101A20; -fx-background-radius: 20; "
                + "-fx-border-color: #26313C; -fx-border-radius: 20; -fx-border-width: 1;");

        AppState.get().isConnected.addListener((o, ov, nv) ->
            javafx.application.Platform.runLater(() -> {
                dot.setFill(nv ? OK_GREEN : ERR_RED);
                label.setText(nv ? "Connected" : "Not connected");
                label.setTextFill(nv ? OK_GREEN : ERR_RED);
            })
        );
        return badge;
    }

    private HBox buildStatusBar() {
        HBox bar = new HBox(20);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 20, 8, 20));
        bar.setStyle("-fx-background-color: #0B1015; -fx-border-color: #26313C; -fx-border-width: 1 0 0 0;");

        Label myIpLabel = new Label("Your IP: " + AppState.get().myIpDisplay.get());
        myIpLabel.setTextFill(Color.web("#5E6B77"));
        myIpLabel.setFont(Font.font("System", 11));
        // DT.1: the real IP is detected on the background init thread; update the
        // label when it lands (myIpDisplay is set via Platform.runLater).
        AppState.get().myIpDisplay.addListener((o, ov, nv) -> myIpLabel.setText("Your IP: " + nv));

        Label sharedLabel = new Label("Shared folder: " + AppState.get().sharedFolderPath.get());
        sharedLabel.setTextFill(Color.web("#5E6B77"));
        sharedLabel.setFont(Font.font("System", 11));
        AppState.get().sharedFolderPath.addListener((o, oldV, newV) -> sharedLabel.setText("Shared folder: " + newV));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // DT.7: the lock used to be an unconditional green "Encrypted". It now tracks the real TLS
        // health (AppState.securityOk): if TLS init failed, the peer server never bound, so no
        // transfers happen at all — the bar says "Encryption unavailable" in red rather than lying.
        Label encLabel = new Label();
        encLabel.setFont(Font.font("System", 11));
        HBox enc = new HBox(6);
        enc.setAlignment(Pos.CENTER);

        Runnable applyEnc = () -> {
            boolean ok = AppState.get().securityOk.get();
            enc.getChildren().setAll(
                    Icons.icon(ok ? Icons.LOCK : Icons.ALERT, 13, ok ? OK_GREEN : ERR_RED, 2.0),
                    encLabel);
            encLabel.setText(ok ? "Encrypted" : "Encryption unavailable");
            encLabel.setTextFill(ok ? OK_GREEN : ERR_RED);
        };
        applyEnc.run();
        AppState.get().securityOk.addListener((o, ov, nv) ->
                javafx.application.Platform.runLater(applyEnc));

        bar.getChildren().addAll(myIpLabel, sharedLabel, spacer, enc);
        return bar;
    }

    private Tab buildTab(String title, String iconPath, Node content) {
        // The icon tracks selection: muted when idle, accent when active — the
        // same cue the CSS gives the label (white + teal underline when selected).
        SVGPath icon = Icons.path(iconPath, 17, TEXT_MUTED, 2.0);
        Tab tab = new Tab(title, content);
        tab.setGraphic(new javafx.scene.Group(icon));
        tab.setStyle("-fx-background-color: #161F28;");
        tab.selectedProperty().addListener((o, ov, selected) ->
                icon.setStroke(selected ? ACCENT : TEXT_MUTED));
        if (tab.isSelected()) icon.setStroke(ACCENT);
        return tab;
    }
}
