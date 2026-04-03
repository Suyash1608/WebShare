package com.webshare.controller;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.effect.Glow;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import javafx.animation.*;
import javafx.util.Duration;

import com.webshare.service.SessionManager;
import com.webshare.service.WebShareServer;
import com.webshare.util.AppLogger;
import com.webshare.util.Utility;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class WebShareApplication extends Application {

    // ══════════════════════════════════════════════════════════════════════
    // Theme
    // ══════════════════════════════════════════════════════════════════════

    static class Theme {
        final String bg, surface, surface2, border, accent, accent2, accent3, text, muted;
        Theme(String bg, String surface, String surface2, String border,
              String accent, String accent2, String accent3, String text, String muted) {
            this.bg = bg; this.surface = surface; this.surface2 = surface2;
            this.border = border; this.accent = accent; this.accent2 = accent2;
            this.accent3 = accent3; this.text = text; this.muted = muted;
        }
    }

    private static final Theme DARK  = new Theme("#0c0d10","#13141a","#1a1c24","#2a2d3a","#4fffb0","#ff6b6b","#4d9fff","#e8eaf0","#6b7280");
    private static final Theme LIGHT = new Theme("#f0f2f5","#ffffff","#eef0f5","#d0d5e0","#00a86b","#e03e3e","#2563eb","#1a1d27","#7a8499");

    private Theme   T      = DARK;
    private boolean isDark = true;

    // ══════════════════════════════════════════════════════════════════════
    // State
    // ══════════════════════════════════════════════════════════════════════

    private volatile boolean powerOn       = false;
    private volatile boolean uploadEnabled = false;
    private volatile boolean execEnabled   = false;

    private WebShareServer server;
    private SessionManager sessionManager;
    private String         serverUrl = "";
    private File           uploadFolder;
    private Thread         watcherThread;

    private Stage      primaryStage;
    private Scene      scene;
    private BorderPane root;

    private Button powerBtn, themeBtn, helpBtn, uploadToggleBtn, execToggleBtn, removeBtn, clearBtn;
    private Label  uploadMainLbl, uploadSubLbl;
    private Canvas folderIconCanvas;

    private Circle    statusDot, powerDot;
    private StackPane toggleTrack, execToggleTrack;
    private javafx.scene.shape.Rectangle toggleTrackRect, execToggleTrackRect;
    private javafx.scene.shape.Rectangle toggleThumb, execToggleThumb;
    private Label uploadToggleLbl, execToggleLbl;

    private Label      statusTxt, urlVal, qrTitle, qrSub, qrPill, keyLabel, countBadge;
    private Canvas     qrCanvas;
    private VBox       qrEmptyBox;
    private StackPane  qrBox;
    private ListView<String> fileList;

    private VBox leftPanel, rightPanel;
    private HBox headerRow, statusRowNode, qrAreaNode;
    private VBox urlRowNode, uploadZoneNode, keyBox;
    private Label headerWeb, headerShare, headerBadge;

    private Timeline pulse;

    private static final int F_LOGO  = 16;
    private static final int F_LABEL = 14;
    private static final int F_SMALL = 12;
    private static final int F_KEY   = 24;
    private static final int F_MONO  = 13;

    // ══════════════════════════════════════════════════════════════════════
    // Application lifecycle
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void start(Stage stage) {
        AppLogger.init();
        this.primaryStage = stage;
        stage.setTitle("WebShare — Local File Server");

        for (int s : new int[]{16, 32, 64, 128, 256}) {
            try {
                stage.getIcons().add(new javafx.scene.image.Image(
                    getClass().getResourceAsStream("/logo.png"), s, s, true, true));
            } catch (Exception ignored) {}
        }

        String folderPath = Utility.createOrGetFolder();
        if (folderPath == null) {
            new Alert(Alert.AlertType.ERROR,
                "Could not create WebShare folder. Please check permissions.")
                .showAndWait();
            Platform.exit();
            return;
        }
        uploadFolder = new File(folderPath);

        root = new BorderPane();
        applyRootStyle();

        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(12));

        headerRow = buildHeader();
        HBox pw   = buildPowerWrap();
        HBox grid = buildGrid();
        VBox.setVgrow(grid, Priority.ALWAYS);

        vbox.getChildren().addAll(headerRow, pw, grid);
        root.setCenter(vbox);

        scene = new Scene(root, 920, 580);
        scene.setFill(Color.web(T.bg));
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setMinWidth(760);
        stage.setMinHeight(520);
        stage.setOnCloseRequest(this::onClose);
        stage.show();

        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = screen.getWidth() / 2;
        double h = screen.getHeight();
        stage.setWidth(w);
        stage.setHeight(h);
        stage.setX((screen.getWidth() - w) / 2);
        stage.setY(screen.getMinY());

        refreshList();
        buildPulse();
    }

    private void onClose(WindowEvent e) { stopServer(); }

    private void stopServer() {
        if (server != null) { server.stopServer(); server = null; }
        if (watcherThread != null) { watcherThread.interrupt(); watcherThread = null; }
        sessionManager = null;
    }

    public static void main(String[] args) { launch(args); }

    // ══════════════════════════════════════════════════════════════════════
    // Help panel
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Shows the help panel — a styled modal with three sections:
     *
     * 1. How to bypass the browser warning (what to do the first time)
     * 2. How to permanently trust the cert per OS (Windows, Android, iPhone, Mac)
     * 3. A plain-language explanation of why the warning appears
     *
     * This lives in JavaFX rather than the browser page because the browser warning
     * appears BEFORE the login page loads — instructions there are useless.
     * The host sees this panel before sharing the URL so they can guide guests.
     */
    private void showHelpPanel() {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.initStyle(StageStyle.UNDECORATED);
        dlg.setResizable(false);

        // ── Header ────────────────────────────────────────────────────────
        Label titleLbl = new Label("Connection & Browser Help");
        titleLbl.setStyle(mono(14, T.accent) + "-fx-font-weight:bold;");

        Button closeBtn = new Button("✕");
        closeBtn.setStyle(
            sans(F_SMALL, T.muted) +
            "-fx-background-color:transparent;-fx-border-color:transparent;" +
            "-fx-cursor:hand;-fx-padding:4 8 4 8;");
        closeBtn.setOnMouseEntered(_ -> closeBtn.setStyle(
            sans(F_SMALL, T.accent2) +
            "-fx-background-color:transparent;-fx-border-color:transparent;-fx-cursor:hand;-fx-padding:4 8 4 8;"));
        closeBtn.setOnMouseExited(_ -> closeBtn.setStyle(
            sans(F_SMALL, T.muted) +
            "-fx-background-color:transparent;-fx-border-color:transparent;-fx-cursor:hand;-fx-padding:4 8 4 8;"));
        closeBtn.setOnAction(_ -> dlg.close());

        Region hSpacer = new Region();
        HBox.setHgrow(hSpacer, Priority.ALWAYS);
        HBox header = new HBox(titleLbl, hSpacer, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        // ── Section 1: Why the warning appears ───────────────────────────
        VBox whySection = makeHelpSection(
            "🔒  Why does the browser show a warning?",
            T.accent,
            "WebShare uses HTTPS — your connection is fully encrypted.\n\n" +
            "The browser warning appears because the certificate was generated locally " +
            "rather than issued by a public Certificate Authority (like those used by " +
            "google.com or amazon.com). The encryption is identical — the browser just " +
            "doesn't recognise who signed it.\n\n" +
            "You have two options: bypass it once each session, or install the certificate " +
            "once to make it permanently trusted on that device."
        );

        // ── Section 2: How to bypass (first time / quick access) ─────────
        VBox bypassSection = makeHelpSection(
            "⚡  Bypass the warning (quick, works every time)",
            T.accent3,
            null // custom content below
        );

        // Browser-specific bypass rows
        String[][] browsers = {
            { "Chrome / Edge",    "Click  Advanced  →  Proceed to site (unsafe)" },
            { "Firefox",          "Click  Advanced  →  Accept the Risk and Continue" },
            { "Safari",           "Click  Show Details  →  visit this website" },
            { "Android Chrome",   "Tap  Advanced  →  Proceed to site" },
            { "Samsung Internet", "Tap  Details  →  Proceed to ... (unsafe)" },
        };

        VBox bypassRows = new VBox(4);
        for (String[] b : browsers) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 10, 6, 10));
            row.setStyle("-fx-background-color:" + T.surface2 + ";-fx-background-radius:6;");

            Label browserLbl = new Label(b[0]);
            browserLbl.setStyle(mono(F_SMALL, T.accent3) + "-fx-font-weight:bold;");
            browserLbl.setMinWidth(130);

            Label stepLbl = new Label(b[1]);
            stepLbl.setStyle(sans(F_SMALL, T.muted));
            stepLbl.setWrapText(true);

            row.getChildren().addAll(browserLbl, stepLbl);
            bypassRows.getChildren().add(row);
        }

        Label bypassNote = new Label(
            "The warning will reappear each time you open a new browser session " +
            "until the certificate is installed — see below.");
        bypassNote.setStyle(sans(11, T.muted));
        bypassNote.setWrapText(true);

        bypassSection.getChildren().addAll(bypassRows, bypassNote);

        // ── Section 3: Permanent fix — OS tabs ───────────────────────────
        VBox permSection = makeHelpSection(
            "✓  Make it permanently trusted (install once)",
            T.accent,
            "Download the certificate from the browser login page and install it " +
            "on the connecting device. After this, no warning will ever appear again " +
            "on that device — same as any trusted website."
        );

        // Tab bar
        String[] osTitles   = { "Windows", "Android", "iPhone/iPad", "Mac" };
        VBox[]   osPanels   = new VBox[4];
        Button[] osBtns     = new Button[4];
        int[]    selected   = { 0 };

        for (int i = 0; i < 4; i++) {
            osBtns[i] = new Button(osTitles[i]);
            int idx = i;
            osBtns[i].setOnAction(_ -> {
                selected[0] = idx;
                updateOsTabs(osBtns, osPanels, idx, T);
            });
        }

        // Windows panel
        osPanels[0] = makeOsPanel(T, new String[][]{
            { "1", "Open the browser login page and click  Download Certificate (.crt)" },
            { "2", "Double-click the downloaded  .crt  file" },
            { "3", "Click  Install Certificate  →  Local Machine  →  Next" },
            { "4", "Choose  Place all certificates in the following store  →  Browse  →  Trusted Root Certification Authorities  →  OK  →  Next  →  Finish" },
            { "5", "Restart your browser — no warning will appear again on this device" },
        }, null);

        // Android panel
        osPanels[1] = makeOsPanel(T, new String[][]{
            { "!", "Screen lock required — Android will not install CA certificates without a PIN, pattern, or password. Set one in  Settings → Security → Screen lock  first." },
            { "1", "Open the browser login page and tap  Download Certificate (.pem)" },
            { "2", "Open  Settings → Security\n  › Pixel / stock Android:  More security settings → Install from storage\n  › Samsung:  More security settings → Install from device storage\n  › Others:  Encryption & credentials → Install a certificate → CA certificate" },
            { "3", "Select the downloaded  .pem  file" },
            { "4", "Name it  WebShare  → tap  CA Certificate  →  Install anyway" },
            { "5", "Restart Chrome — no warning will appear again on this device" },
        }, null);

        // iPhone panel
        osPanels[2] = makeOsPanel(T, new String[][]{
            { "1", "Open the browser login page and tap  Download Certificate (.mobileconfig)  →  tap  Allow" },
            { "2", "Open  Settings  →  tap  Profile Downloaded  →  Install  →  enter your passcode" },
            { "3", "Settings  →  General  →  About  →  Certificate Trust Settings" },
            { "4", "Toggle  WebShare  on  →  Done — no warning will appear again on this device" },
        }, null);

        // Mac panel
        osPanels[3] = makeOsPanel(T, new String[][]{
            { "1", "Open the browser login page and click  Download Certificate (.pem)" },
            { "2", "Double-click the  .pem  file — Keychain Access opens automatically" },
            { "3", "Add to the  System  keychain" },
            { "4", "Find  WebShare  in the list → double-click → expand  Trust  → set  Always Trust" },
            { "5", "Enter your Mac password → Restart browser — no warning will appear again on this device" },
        }, null);

        HBox tabBar = new HBox(4);
        tabBar.getChildren().addAll(osBtns);

        StackPane tabContent = new StackPane();
        for (VBox p : osPanels) {
            p.setVisible(false);
            p.setManaged(false);
            tabContent.getChildren().add(p);
        }

        updateOsTabs(osBtns, osPanels, 0, T); // show Windows by default

        permSection.getChildren().addAll(tabBar, tabContent);

        // ── Scroll container ──────────────────────────────────────────────
        VBox allSections = new VBox(14, whySection, bypassSection, permSection);
        allSections.setPadding(new Insets(0, 2, 4, 2));

        ScrollPane scroll = new ScrollPane(allSections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;" +
            "-fx-border-color:transparent;");
        scroll.setPrefHeight(460);

        // ── Root layout ───────────────────────────────────────────────────
        VBox rootBox = new VBox(16, header, scroll);
        rootBox.setPadding(new Insets(24));
        rootBox.setPrefWidth(580);
        rootBox.setStyle(
            "-fx-background-color:" + T.surface + ";" +
            "-fx-border-color:" + T.border + ";" +
            "-fx-border-radius:16;-fx-background-radius:16;-fx-border-width:1;");

        Scene dlgScene = new Scene(rootBox);
        dlgScene.setFill(Color.web(T.bg));
        dlg.setScene(dlgScene);
        dlg.showAndWait();
    }

    /**
     * Creates a styled section card with a coloured title and optional body text.
     * Extra content (e.g. rows, tabs) can be added to the returned VBox after the call.
     */
    private VBox makeHelpSection(String title, String accentColor, String bodyText) {
        Label titleLbl = new Label(title);
        titleLbl.setStyle(sans(F_LABEL, accentColor) + "-fx-font-weight:600;");
        titleLbl.setWrapText(true);

        VBox box = new VBox(10);
        box.setPadding(new Insets(14, 16, 14, 16));
        box.setStyle(
            "-fx-background-color:" + T.surface2 + ";" +
            "-fx-border-color:" + T.border + ";" +
            "-fx-border-radius:10;-fx-background-radius:10;-fx-border-width:1;");
        box.getChildren().add(titleLbl);

        if (bodyText != null) {
            Label body = new Label(bodyText);
            body.setStyle(sans(F_SMALL, T.muted));
            body.setWrapText(true);
            box.getChildren().add(body);
        }
        return box;
    }

    /**
     * Builds the step list for one OS panel.
     * Steps with index "!" are rendered as amber warning rows.
     */
    private VBox makeOsPanel(Theme t, String[][] steps, String note) {
        VBox panel = new VBox(4);
        panel.setPadding(new Insets(10, 0, 4, 0));

        for (String[] step : steps) {
            boolean isWarn = step[0].equals("!");
            HBox row = new HBox(10);
            row.setAlignment(Pos.TOP_LEFT);
            row.setPadding(new Insets(6, 10, 6, 10));
            row.setStyle("-fx-background-color:" + (isWarn ? "rgba(245,158,11,0.07)" : t.bg) +
                ";-fx-background-radius:6;" +
                (isWarn ? "-fx-border-color:rgba(245,158,11,0.2);-fx-border-radius:6;-fx-border-width:1;" : ""));

            Label num = new Label(step[0]);
            num.setStyle(mono(F_SMALL, isWarn ? "#f59e0b" : t.accent) + "-fx-font-weight:bold;");
            num.setMinWidth(20);

            Label txt = new Label(step[1]);
            txt.setStyle(sans(F_SMALL, isWarn ? "#a07030" : t.muted));
            txt.setWrapText(true);
            HBox.setHgrow(txt, Priority.ALWAYS);

            row.getChildren().addAll(num, txt);
            panel.getChildren().add(row);
        }

        if (note != null) {
            Label noteLbl = new Label(note);
            noteLbl.setStyle(sans(11, T.muted));
            noteLbl.setWrapText(true);
            noteLbl.setPadding(new Insets(6, 0, 0, 0));
            panel.getChildren().add(noteLbl);
        }
        return panel;
    }

    /** Updates OS tab button styles and shows the selected panel. */
    private void updateOsTabs(Button[] btns, VBox[] panels, int selectedIdx, Theme t) {
        for (int i = 0; i < btns.length; i++) {
            boolean active = (i == selectedIdx);
            btns[i].setStyle(
                mono(F_SMALL, active ? t.accent : t.muted) +
                "-fx-background-color:" + (active ? "rgba(78,255,176,0.1)" : t.surface2) + ";" +
                "-fx-border-color:" + (active ? t.accent : t.border) + ";" +
                "-fx-border-radius:8;-fx-background-radius:8;-fx-border-width:1;" +
                "-fx-padding:6 12 6 12;-fx-cursor:hand;");
            panels[i].setVisible(active);
            panels[i].setManaged(active);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Theme
    // ══════════════════════════════════════════════════════════════════════

    private void applyRootStyle() { root.setStyle("-fx-background-color:" + T.bg + ";"); }

    private void toggleTheme() {
        isDark = !isDark;
        T = isDark ? DARK : LIGHT;
        themeBtn.setText(isDark ? "☀  Light" : "☾  Dark");
        applyRootStyle();
        scene.setFill(Color.web(T.bg));
        refreshAllStyles();
    }

    private void refreshAllStyles() {
        headerWeb.setStyle(mono(F_LOGO, T.text) + "-fx-font-weight:bold;");
        headerShare.setStyle(mono(F_LOGO, T.accent) + "-fx-font-weight:bold;");
        headerBadge.setStyle(mono(F_SMALL, T.muted) + "-fx-background-color:" + T.surface2 +
            ";-fx-border-color:" + T.border + ";-fx-background-radius:20;-fx-border-radius:20;-fx-padding:4 12 4 12;");
        styleThemeBtn(); styleHelpBtn(); stylePowerBtn();
        styleUploadToggle(); styleExecToggle(); styleActionBtns();
        stylePanel(leftPanel); stylePanel(rightPanel);
        if (statusRowNode  != null) statusRowNode.setStyle("-fx-background-color:" + T.surface2 + ";-fx-background-radius:10;");
        if (urlRowNode     != null) urlRowNode.setStyle("-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border + ";-fx-border-radius:10;-fx-background-radius:10;-fx-border-width:1;");
        if (qrAreaNode     != null) qrAreaNode.setStyle("-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border + ";-fx-border-width:1;-fx-border-style:dashed;-fx-border-radius:10;-fx-background-radius:10;");
        if (uploadZoneNode != null) uploadZoneNode.setStyle("-fx-border-color:" + T.border + ";-fx-border-radius:10;-fx-border-width:1.5;-fx-border-style:dashed;-fx-cursor:hand;");
        fileList.setStyle(fileListCss()); fileList.refresh();
        if (countBadge    != null) countBadge.setStyle(sans(F_SMALL, T.muted) + "-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border + ";-fx-background-radius:20;-fx-border-radius:20;-fx-padding:4 12 4 12;");
        if (uploadMainLbl != null) uploadMainLbl.setStyle(sans(F_LABEL, T.text) + "-fx-font-weight:500;");
        if (uploadSubLbl  != null) uploadSubLbl.setStyle(sans(F_SMALL, T.muted));
        if (folderIconCanvas != null) drawFolderIcon(folderIconCanvas);
        urlVal.setStyle(urlLabelStyle());
        qrTitle.setStyle(sans(F_LABEL, T.text) + "-fx-font-weight:bold;");
        qrSub.setStyle(sans(F_SMALL, T.muted));
        statusTxt.setStyle(sans(F_LABEL, powerOn ? T.accent : T.text));
        if (keyBox   != null) keyBox.setStyle("-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border + ";-fx-border-radius:10;-fx-background-radius:10;-fx-border-width:1;");
        if (keyLabel != null) keyLabel.setStyle(mono(F_KEY, T.accent) + "-fx-font-weight:bold;");
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI builders
    // ══════════════════════════════════════════════════════════════════════

    private HBox buildHeader() {
        StackPane icon = new StackPane();
        icon.setPrefSize(36, 36); icon.setMinSize(36, 36); icon.setMaxSize(36, 36);
        try {
            javafx.scene.image.ImageView lv = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(getClass().getResourceAsStream("/logo.png")));
            lv.setFitWidth(36); lv.setFitHeight(36);
            lv.setPreserveRatio(true); lv.setSmooth(true);
            icon.getChildren().add(lv);
        } catch (Exception ex) {
            Rectangle bg = new Rectangle(36, 36);
            bg.setArcWidth(10); bg.setArcHeight(10);
            bg.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web(T.accent)), new Stop(1, Color.web(T.accent3))));
            Label em = new Label("WS");
            em.setStyle(mono(13, "white") + "-fx-font-weight:bold;");
            icon.getChildren().addAll(bg, em);
        }

        headerWeb   = new Label("Web");
        headerShare = new Label("Share");
        headerWeb.setStyle(mono(F_LOGO, T.text) + "-fx-font-weight:bold;");
        headerShare.setStyle(mono(F_LOGO, T.accent) + "-fx-font-weight:bold;");
        headerBadge = new Label("LOCAL FILE SERVER");
        headerBadge.setStyle(mono(F_SMALL, T.muted) +
            "-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border +
            ";-fx-background-radius:20;-fx-border-radius:20;-fx-padding:4 12 4 12;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        // Help button — "?" — opens the help panel
        helpBtn = new Button("?  Help");
        styleHelpBtn();
        helpBtn.setOnAction(_ -> showHelpPanel());

        themeBtn = new Button(isDark ? "☀  Light" : "☾  Dark");
        styleThemeBtn();
        themeBtn.setOnAction(_ -> toggleTheme());

        HBox row = new HBox(8, icon, headerWeb, headerShare, headerBadge, sp, helpBtn, themeBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void styleHelpBtn() {
        if (helpBtn == null) return;
        helpBtn.setStyle(sans(F_SMALL, T.accent3) +
            "-fx-background-color:rgba(77,159,255,0.08);" +
            "-fx-border-color:rgba(77,159,255,0.3);" +
            ";-fx-background-radius:20;-fx-border-radius:20;-fx-padding:6 14 6 14;-fx-cursor:hand;");
    }

    private void styleThemeBtn() {
        if (themeBtn == null) return;
        themeBtn.setStyle(sans(F_SMALL, T.muted) +
            "-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border +
            ";-fx-background-radius:20;-fx-border-radius:20;-fx-padding:6 16 6 16;-fx-cursor:hand;");
    }

    private HBox buildPowerWrap() {
        powerDot = new Circle(7, Color.web("#e03e3e"));
        Label powerLbl = new Label("START");
        powerLbl.setStyle(sans(F_SMALL, T.muted));
        HBox powerContent = new HBox(8, powerDot, powerLbl);
        powerContent.setAlignment(Pos.CENTER);
        powerBtn = new Button();
        powerBtn.setGraphic(powerContent);
        powerBtn.setPrefHeight(38);
        stylePowerBtn();
        powerBtn.setOnAction(_ -> togglePower());

        uploadToggleLbl = new Label("UPLOADS");
        uploadToggleLbl.setStyle(sans(F_SMALL, T.muted));
        toggleTrackRect = new javafx.scene.shape.Rectangle(40, 20);
        toggleTrackRect.setArcWidth(20); toggleTrackRect.setArcHeight(20);
        toggleTrackRect.setFill(Color.web(T.border));
        toggleThumb = new javafx.scene.shape.Rectangle(16, 16);
        toggleThumb.setArcWidth(16); toggleThumb.setArcHeight(16);
        toggleThumb.setFill(Color.WHITE); toggleThumb.setTranslateX(-10);
        toggleTrack = new StackPane(toggleTrackRect, toggleThumb);
        toggleTrack.setPrefSize(40, 20); toggleTrack.setMaxSize(40, 20);
        HBox uploadContent = new HBox(8, uploadToggleLbl, toggleTrack);
        uploadContent.setAlignment(Pos.CENTER);
        uploadToggleBtn = new Button();
        uploadToggleBtn.setGraphic(uploadContent);
        uploadToggleBtn.setPrefHeight(38);
        uploadToggleBtn.setDisable(true);
        styleUploadToggle();
        uploadToggleBtn.setOnAction(_ -> toggleUpload());

        execToggleLbl = new Label("EXEC FILES");
        execToggleLbl.setStyle(sans(F_SMALL, T.muted));
        execToggleTrackRect = new javafx.scene.shape.Rectangle(40, 20);
        execToggleTrackRect.setArcWidth(20); execToggleTrackRect.setArcHeight(20);
        execToggleTrackRect.setFill(Color.web(T.border));
        execToggleThumb = new javafx.scene.shape.Rectangle(16, 16);
        execToggleThumb.setArcWidth(16); execToggleThumb.setArcHeight(16);
        execToggleThumb.setFill(Color.WHITE); execToggleThumb.setTranslateX(-10);
        execToggleTrack = new StackPane(execToggleTrackRect, execToggleThumb);
        execToggleTrack.setPrefSize(40, 20); execToggleTrack.setMaxSize(40, 20);
        HBox execContent = new HBox(8, execToggleLbl, execToggleTrack);
        execContent.setAlignment(Pos.CENTER);
        execToggleBtn = new Button();
        execToggleBtn.setGraphic(execContent);
        execToggleBtn.setPrefHeight(38);
        execToggleBtn.setDisable(true);
        styleExecToggle();
        execToggleBtn.setOnAction(_ -> toggleExec());

        HBox wrap = new HBox(12, powerBtn, uploadToggleBtn, execToggleBtn);
        wrap.setAlignment(Pos.CENTER);
        return wrap;
    }

    private void stylePowerBtn() {
        if (powerBtn == null) return;
        powerBtn.setStyle(sans(F_SMALL, powerOn ? T.accent : T.muted) +
            "-fx-background-color:" + T.surface2 + ";-fx-border-color:" + (powerOn ? T.accent : T.border) +
            ";-fx-background-radius:20;-fx-border-radius:20;-fx-border-width:1;" +
            "-fx-padding:6 16 6 16;-fx-cursor:hand;");
        if (powerDot != null) {
            powerDot.setFill(powerOn ? Color.web("#e03e3e") : Color.web("#4fffb0"));
            powerDot.setEffect(powerOn ? new Glow(0.8) : null);
        }
        HBox content = (HBox) powerBtn.getGraphic();
        if (content != null) {
            Label lbl = (Label) content.getChildren().get(1);
            lbl.setText(powerOn ? "STOP" : "START");
            lbl.setStyle(sans(F_SMALL, powerOn ? T.accent : T.muted));
        }
    }

    private HBox buildGrid() {
        leftPanel  = buildLeftPanel();
        rightPanel = buildRightPanel();
        HBox.setHgrow(leftPanel,  Priority.ALWAYS);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);
        leftPanel.setPrefWidth(300);
        rightPanel.setPrefWidth(500);
        HBox grid = new HBox(14, leftPanel, rightPanel);
        VBox.setVgrow(grid, Priority.ALWAYS);
        return grid;
    }

    private VBox buildLeftPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(12));
        stylePanel(panel);
        panel.getChildren().add(sectionTitle("SERVER STATUS"));

        statusDot = new Circle(6, Color.web("#e03e3e"));
        statusTxt = new Label("Server offline");
        statusTxt.setStyle(sans(F_LABEL, T.text));
        statusRowNode = new HBox(10, statusDot, statusTxt);
        statusRowNode.setAlignment(Pos.CENTER_LEFT);
        statusRowNode.setPadding(new Insets(10, 14, 10, 14));
        statusRowNode.setStyle("-fx-background-color:" + T.surface2 + ";-fx-background-radius:10;");

        Label uLbl = new Label("SERVER URL");
        uLbl.setStyle(mono(F_SMALL, T.muted) + "-fx-font-weight:bold;");
        urlVal = new Label("—");
        urlVal.setStyle(urlLabelStyle());
        urlVal.setWrapText(true);
        urlVal.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(urlVal, Priority.ALWAYS);

        Button copyBtn = new Button("⎘");
        copyBtn.setStyle(mono(F_SMALL, T.muted) +
            "-fx-background-color:transparent;-fx-border-color:transparent;-fx-cursor:hand;-fx-padding:2 6 2 6;");
        copyBtn.setTooltip(new Tooltip("Copy URL"));
        copyBtn.setOnMouseEntered(_ -> copyBtn.setStyle(mono(F_SMALL, T.accent) +
            "-fx-background-color:transparent;-fx-border-color:transparent;-fx-cursor:hand;-fx-padding:2 6 2 6;"));
        copyBtn.setOnMouseExited(_ -> copyBtn.setStyle(mono(F_SMALL, T.muted) +
            "-fx-background-color:transparent;-fx-border-color:transparent;-fx-cursor:hand;-fx-padding:2 6 2 6;"));
        copyBtn.setOnAction(_ -> {
            String url = urlVal.getText();
            if (url != null && !url.equals("—") && !url.isEmpty()) {
                javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(url); cb.setContent(cc);
                copyBtn.setText("✓");
                new Timeline(new KeyFrame(Duration.seconds(2), _ -> copyBtn.setText("⎘"))).play();
            }
        });

        HBox urlRow = new HBox(4, urlVal, copyBtn);
        urlRow.setAlignment(Pos.TOP_LEFT);
        urlRowNode = new VBox(4, uLbl, urlRow);
        urlRowNode.setPadding(new Insets(10, 14, 10, 14));
        urlRowNode.setStyle("-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border +
            ";-fx-border-radius:10;-fx-background-radius:10;-fx-border-width:1;");

        Label kLbl = new Label("ACCESS KEY");
        kLbl.setStyle(mono(F_SMALL, T.muted) + "-fx-font-weight:bold;");
        keyLabel = new Label("——————");
        keyLabel.setStyle(mono(F_KEY, T.accent) + "-fx-font-weight:bold;");
        keyLabel.setMaxWidth(Double.MAX_VALUE);
        keyBox = new VBox(5, kLbl, keyLabel);
        keyBox.setPadding(new Insets(10, 14, 10, 14));
        keyBox.setStyle("-fx-background-color:" + T.surface2 + ";-fx-border-color:" + T.border +
            ";-fx-border-radius:10;-fx-background-radius:10;-fx-border-width:1;");

        qrTitle = new Label("Waiting for server");
        qrTitle.setStyle(sans(F_LABEL, T.text) + "-fx-font-weight:bold;");
        qrTitle.setMaxWidth(Double.MAX_VALUE);
        qrTitle.setAlignment(Pos.CENTER);

        qrEmptyBox = buildQrEmpty();
        qrCanvas   = new Canvas(150, 150);
        qrCanvas.setVisible(false);
        qrBox = new StackPane(qrEmptyBox, qrCanvas);
        qrBox.setPrefSize(154, 154); qrBox.setMinSize(154, 154); qrBox.setMaxSize(154, 154);
        qrBox.setPadding(new Insets(2));
        qrBox.setStyle("-fx-background-color:white;-fx-background-radius:10;");

        qrSub = new Label("Enter access key in browser");
        qrSub.setStyle(sans(F_SMALL, T.muted));
        qrSub.setMaxWidth(Double.MAX_VALUE);
        qrSub.setAlignment(Pos.CENTER);

        qrPill = new Label(""); qrPill.setVisible(false); qrPill.setManaged(false);

        HBox qrBoxWrap = new HBox(qrBox);
        qrBoxWrap.setAlignment(Pos.CENTER);
        VBox qrVBox = new VBox(8, qrTitle, qrBoxWrap, qrSub);
        qrVBox.setAlignment(Pos.CENTER);
        qrVBox.setMaxWidth(Double.MAX_VALUE);

        qrAreaNode = new HBox(qrVBox);
        qrAreaNode.setAlignment(Pos.CENTER);
        qrAreaNode.setPadding(new Insets(14));
        qrAreaNode.setStyle("-fx-background-color:" + T.surface2 +
            ";-fx-border-color:" + T.border + ";-fx-border-width:1;-fx-border-style:dashed;" +
            "-fx-border-radius:10;-fx-background-radius:10;");
        VBox.setVgrow(qrAreaNode, Priority.ALWAYS);
        HBox.setHgrow(qrVBox, Priority.ALWAYS);

        panel.getChildren().addAll(statusRowNode, urlRowNode, keyBox, qrAreaNode);
        return panel;
    }

    private String urlLabelStyle() {
        return mono(F_MONO, T.accent3) + "-fx-font-weight:bold;";
    }

    private VBox buildQrEmpty() {
        Label i = new Label("▦");
        i.setStyle("-fx-font-size:48px;-fx-text-fill:#aaaaaa;-fx-opacity:0.2;");
        Label t = new Label("Scan from any\ndevice on your\nnetwork");
        t.setStyle(sans(F_SMALL, "#999999") + "-fx-text-alignment:center;");
        t.setAlignment(Pos.CENTER);
        VBox b = new VBox(6, i, t);
        b.setAlignment(Pos.CENTER);
        return b;
    }

    private VBox buildRightPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(12));
        stylePanel(panel);
        VBox.setVgrow(panel, Priority.ALWAYS);
        panel.getChildren().add(sectionTitle("SHARED FILES"));

        uploadZoneNode = new VBox(6);
        uploadZoneNode.setAlignment(Pos.CENTER);
        uploadZoneNode.setPadding(new Insets(12));
        uploadZoneNode.setMaxWidth(Double.MAX_VALUE);
        uploadZoneNode.setStyle("-fx-border-color:" + T.border +
            ";-fx-border-radius:10;-fx-border-width:1.5;-fx-border-style:dashed;-fx-cursor:hand;");

        folderIconCanvas = buildFolderIcon();
        uploadMainLbl = new Label("Click to upload files");
        uploadMainLbl.setStyle(sans(F_LABEL, T.text) + "-fx-font-weight:500;");
        uploadSubLbl = new Label("All file types supported");
        uploadSubLbl.setStyle(sans(F_SMALL, T.muted));
        uploadZoneNode.getChildren().addAll(folderIconCanvas, uploadMainLbl, uploadSubLbl);
        uploadZoneNode.setOnMouseClicked(_ -> handleUpload());
        uploadZoneNode.setOnMouseEntered(_ -> uploadZoneNode.setStyle(
            "-fx-background-color:rgba(78,255,176,0.04);-fx-border-color:" + T.accent +
            ";-fx-border-radius:10;-fx-border-width:1.5;-fx-border-style:dashed;-fx-cursor:hand;"));
        uploadZoneNode.setOnMouseExited(_ -> uploadZoneNode.setStyle(
            "-fx-border-color:" + T.border + ";-fx-border-radius:10;-fx-border-width:1.5;-fx-border-style:dashed;-fx-cursor:hand;"));

        countBadge = new Label("0 files");
        countBadge.setStyle(sans(F_SMALL, T.muted) + "-fx-background-color:" + T.surface2 +
            ";-fx-border-color:" + T.border + ";-fx-background-radius:20;-fx-border-radius:20;-fx-padding:4 12 4 12;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox listHdr = new HBox(sectionTitle("FILES"), sp, countBadge);
        listHdr.setAlignment(Pos.CENTER_LEFT);

        fileList = new ListView<>();
        fileList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(fileList, Priority.ALWAYS);
        fileList.setStyle(fileListCss());
        fileList.setCellFactory(_ -> buildCell());

        removeBtn = actionBtn("Remove Selected");
        clearBtn  = actionBtn("Clear All");
        removeBtn.setOnAction(_ -> confirmThenRun(
            "Remove selected files?", "This cannot be undone.", this::removeSelected));
        clearBtn.setOnAction(_ -> confirmThenRun(
            "Clear all files?", "This will delete everything in the share folder.", this::clearAll));

        HBox.setHgrow(removeBtn, Priority.ALWAYS);
        HBox.setHgrow(clearBtn,  Priority.ALWAYS);
        removeBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        HBox acts = new HBox(10, removeBtn, clearBtn);

        panel.getChildren().addAll(uploadZoneNode, listHdr, fileList, acts);
        return panel;
    }

    private String fileListCss() {
        return "-fx-background-color:" + T.surface2 + ";-fx-background-radius:10;" +
               "-fx-border-color:" + T.border + ";-fx-border-radius:10;-fx-border-width:1;";
    }

    private ListCell<String> buildCell() {
        return new ListCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null); setGraphic(null);
                    setStyle("-fx-background-color:transparent;"); return;
                }
                int    lp    = item.lastIndexOf(" (");
                String fname = lp >= 0 ? item.substring(0, lp).trim() : item.trim();
                String size  = lp >= 0 ? item.substring(lp + 2).replace(")", "") : "";
                String ext   = fname.contains(".")
                    ? fname.substring(fname.lastIndexOf('.') + 1).toUpperCase() : "FILE";
                if (ext.length() > 5) ext = ext.substring(0, 5);

                Label eb = new Label(ext);
                eb.setStyle(mono(F_SMALL, T.accent) +
                    "-fx-background-color:rgba(78,255,176,0.12);-fx-background-radius:5;" +
                    "-fx-padding:3 8 3 8;-fx-min-width:40;-fx-alignment:center;");
                Label nl = new Label(fname);
                nl.setStyle(sans(F_LABEL, T.text));
                nl.setMaxWidth(Double.MAX_VALUE); nl.setEllipsisString("…");
                HBox.setHgrow(nl, Priority.ALWAYS);
                Label sl = new Label(size);
                sl.setStyle(mono(F_SMALL, T.muted));

                String fn = fname;
                Button del = new Button("✕");
                del.setStyle("-fx-background-color:transparent;-fx-border-color:transparent;" +
                    "-fx-text-fill:" + T.muted + ";-fx-font-size:13px;" +
                    "-fx-cursor:hand;-fx-padding:0 4 0 4;-fx-min-width:22;");
                del.setOnAction(_ -> confirmThenRun("Delete " + fn + "?", "This cannot be undone.",
                    () -> { new File(uploadFolder, fn).delete(); refreshList(); broadcast(); }));

                HBox row = new HBox(10, eb, nl, sl, del);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(5, 10, 5, 10));
                setGraphic(row); setText(null);
                setStyle(isSelected()
                    ? "-fx-background-color:rgba(77,159,255,0.12);"
                    : "-fx-background-color:transparent;");
            }
        };
    }

    private Canvas buildFolderIcon() {
        Canvas c = new Canvas(32, 28); drawFolderIcon(c); return c;
    }

    private void drawFolderIcon(Canvas c) {
        GraphicsContext gc = c.getGraphicsContext2D();
        gc.clearRect(0, 0, c.getWidth(), c.getHeight());
        gc.setFill(Color.web(T.accent));
        gc.fillRoundRect(0, 8, 32, 20, 4, 4);
        gc.fillRoundRect(0, 4, 14, 8, 4, 4);
    }

    private void stylePanel(VBox p) {
        p.setStyle("-fx-background-color:" + T.surface + ";-fx-border-color:" + T.border +
            ";-fx-border-radius:14;-fx-background-radius:14;-fx-border-width:1;");
    }

    private HBox sectionTitle(String text) {
        Rectangle line = new Rectangle(14, 1.5);
        line.setFill(Color.web(T.border));
        Label lbl = new Label(text);
        lbl.setStyle(sans(F_SMALL, T.muted));
        HBox box = new HBox(8, line, lbl);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Button actionBtn(String text) {
        Button b = new Button(text);
        b.setStyle(actionBaseStyle());
        b.setOnMouseEntered(_ -> b.setStyle(actionHoverStyle()));
        b.setOnMouseExited(_  -> b.setStyle(actionBaseStyle()));
        return b;
    }

    private String actionBaseStyle() {
        return sans(F_SMALL, T.muted) + "-fx-font-weight:600;-fx-background-color:" + T.surface2 +
            ";-fx-border-color:" + T.border + ";-fx-border-radius:8;-fx-background-radius:8;" +
            "-fx-border-width:1;-fx-padding:10 20 10 20;-fx-cursor:hand;";
    }

    private String actionHoverStyle() {
        return sans(F_SMALL, T.accent2) + "-fx-font-weight:600;" +
            "-fx-background-color:rgba(255,107,107,0.07);" +
            "-fx-border-color:" + T.accent2 + ";-fx-border-radius:8;-fx-background-radius:8;" +
            "-fx-border-width:1;-fx-padding:10 20 10 20;-fx-cursor:hand;";
    }

    private void styleActionBtns() {
        if (removeBtn != null) { removeBtn.setStyle(actionBaseStyle()); removeBtn.setOnMouseEntered(_ -> removeBtn.setStyle(actionHoverStyle())); removeBtn.setOnMouseExited(_ -> removeBtn.setStyle(actionBaseStyle())); }
        if (clearBtn  != null) { clearBtn.setStyle(actionBaseStyle());  clearBtn.setOnMouseEntered(_ -> clearBtn.setStyle(actionHoverStyle()));  clearBtn.setOnMouseExited(_ -> clearBtn.setStyle(actionBaseStyle())); }
    }

    private String mono(int s, String c) { return "-fx-font-family:'Courier New';-fx-font-size:" + s + "px;-fx-text-fill:" + c + ";"; }
    private String sans(int s, String c) { return "-fx-font-size:" + s + "px;-fx-text-fill:" + c + ";"; }

    // ══════════════════════════════════════════════════════════════════════
    // Server control
    // ══════════════════════════════════════════════════════════════════════

    private void buildPulse() {
        pulse = new Timeline(
            new KeyFrame(Duration.ZERO,        new KeyValue(statusDot.opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(900),  new KeyValue(statusDot.opacityProperty(), 0.2)),
            new KeyFrame(Duration.millis(1800), new KeyValue(statusDot.opacityProperty(), 1.0)));
        pulse.setCycleCount(Animation.INDEFINITE);
    }

    private void togglePower() {
        powerBtn.setDisable(true);
        powerOn = !powerOn;

        FadeTransition ft = new FadeTransition(Duration.millis(250), statusTxt);
        ft.setFromValue(0.2); ft.setToValue(1.0); ft.play();

        if (powerOn) {
            stylePowerBtn();
            statusDot.setFill(Color.web(T.accent));
            statusDot.setEffect(new Glow(0.9));
            statusTxt.setStyle(sans(F_LABEL, T.accent));
            statusTxt.setText("Starting…");
            uploadToggleBtn.setDisable(false);
            execToggleBtn.setDisable(false);
            pulse.play();
            Thread t = new Thread(this::startServer);
            t.setDaemon(true); t.setName("server-start"); t.start();
        } else {
            stylePowerBtn();
            statusDot.setFill(Color.web("#e03e3e"));
            statusDot.setEffect(null);
            statusTxt.setStyle(sans(F_LABEL, T.text));
            statusTxt.setText("Server offline");
            uploadToggleBtn.setDisable(true);
            execToggleBtn.setDisable(true);
            pulse.stop();
            stopServer();
            serverUrl = ""; uploadEnabled = false; execEnabled = false;
            styleUploadToggle(); styleExecToggle();
            keyLabel.setText("——————");
            urlVal.setText("—"); urlVal.setStyle(urlLabelStyle());
            qrCanvas.setVisible(false); qrEmptyBox.setVisible(true);
            qrTitle.setText("Waiting for server");
            qrSub.setText("Enter access key in browser");
            refreshList();
            powerBtn.setDisable(false);
        }
    }

    private void startServer() {
    try {
        sessionManager = new SessionManager();

        File jksFile = new File(Utility.getJksPath());
        if (!jksFile.exists() || jksFile.length() == 0) {
            Platform.runLater(() -> statusTxt.setText("Generating certificate…"));
            Utility.CertSetupResult certResult = Utility.ensureJksExists();
            if (certResult == Utility.CertSetupResult.FAILED) {
                Platform.runLater(() -> {
                    statusTxt.setText("✗ Certificate generation failed");
                    powerOn = false; stylePowerBtn(); powerBtn.setDisable(false);
                });
                return;
            }
        }

        server = new WebShareServer(uploadFolder, sessionManager);
        String result = server.startServer();

        if (result.startsWith("Failed")) {
            Platform.runLater(() -> {
                statusTxt.setText("✗ " + result);
                powerOn = false; stylePowerBtn(); powerBtn.setDisable(false);
            });
            return;
        }

        serverUrl = result.trim();

        Platform.runLater(() -> {
            urlVal.setText(serverUrl);
            urlVal.setStyle(urlLabelStyle());
            keyLabel.setText(sessionManager.getAccessKey());
            statusTxt.setText("Online — ready to share");
            qrTitle.setText("Scan to connect");
            qrSub.setText("Key: " + sessionManager.getAccessKey());
            generateQR(serverUrl);
            refreshList();
            startFolderWatcher();
            powerBtn.setDisable(false);
        });

    } catch (Exception ex) {
        AppLogger.error("Server start failed", ex);
        Platform.runLater(() -> {
            statusTxt.setText("✗ " + ex.getMessage());
            powerOn = false; stylePowerBtn(); powerBtn.setDisable(false);
        });
    }
}

    private void generateQR(String url) {
        GraphicsContext gc = qrCanvas.getGraphicsContext2D();
        int sz = 150;
        gc.setFill(Color.WHITE); gc.fillRoundRect(0, 0, sz, sz, 8, 8);
        try {
            Hashtable<EncodeHintType, ErrorCorrectionLevel> h = new Hashtable<>();
            h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sz, sz, h);
            int w = m.getWidth(); double cell = (double) sz / w;
            for (int i = 0; i < w; i++)
                for (int j = 0; j < w; j++) {
                    gc.setFill(m.get(i, j) ? Color.BLACK : Color.WHITE);
                    gc.fillRect(i * cell, j * cell, cell, cell);
                }
            qrEmptyBox.setVisible(false); qrCanvas.setVisible(true);
        } catch (WriterException ex) {
            gc.setFill(Color.RED); gc.fillText("QR Error", 10, 80);
        }
    }

    private void toggleUpload() {
        if (!powerOn) return;
        uploadEnabled = !uploadEnabled;
        if (sessionManager != null) sessionManager.setUploadAllowed(uploadEnabled);
        styleUploadToggle();
    }

    private void styleUploadToggle() {
        if (uploadToggleBtn == null) return;
        uploadToggleBtn.setStyle(sans(F_SMALL, uploadEnabled ? T.accent : T.muted) +
            "-fx-background-color:" + T.surface2 + ";-fx-border-color:" + (uploadEnabled ? T.accent : T.border) +
            ";-fx-background-radius:20;-fx-border-radius:20;-fx-border-width:1;-fx-padding:6 16 6 16;-fx-cursor:hand;");
        if (uploadToggleLbl  != null) uploadToggleLbl.setStyle(sans(F_SMALL, uploadEnabled ? T.accent : T.muted));
        if (toggleTrackRect  != null) toggleTrackRect.setFill(uploadEnabled ? Color.web(T.accent) : Color.web(T.border));
        if (toggleThumb      != null) toggleThumb.setTranslateX(uploadEnabled ? 10 : -10);
    }

    private void toggleExec() {
        if (!powerOn) return;
        execEnabled = !execEnabled;
        if (sessionManager != null) sessionManager.setExecAllowed(execEnabled);
        styleExecToggle(); broadcast();
    }

    private void styleExecToggle() {
        if (execToggleBtn == null) return;
        execToggleBtn.setStyle(sans(F_SMALL, execEnabled ? T.accent2 : T.muted) +
            "-fx-background-color:" + T.surface2 + ";-fx-border-color:" + (execEnabled ? T.accent2 : T.border) +
            ";-fx-background-radius:20;-fx-border-radius:20;-fx-border-width:1;-fx-padding:6 16 6 16;-fx-cursor:hand;");
        if (execToggleLbl       != null) execToggleLbl.setStyle(sans(F_SMALL, execEnabled ? T.accent2 : T.muted));
        if (execToggleTrackRect != null) execToggleTrackRect.setFill(execEnabled ? Color.web(T.accent2) : Color.web(T.border));
        if (execToggleThumb     != null) execToggleThumb.setTranslateX(execEnabled ? 10 : -10);
    }

    // ══════════════════════════════════════════════════════════════════════
    // File watcher
    // ══════════════════════════════════════════════════════════════════════

    private void startFolderWatcher() {
        if (watcherThread != null && watcherThread.isAlive()) watcherThread.interrupt();
        if (!uploadFolder.exists()) uploadFolder.mkdirs();

        watcherThread = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                Path dir = uploadFolder.toPath();
                dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey k = ws.take();
                    k.pollEvents(); k.reset();
                    Platform.runLater(this::refreshList);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                AppLogger.error("Watcher error", e);
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.setName("folder-watcher");
        watcherThread.start();
    }

    // ══════════════════════════════════════════════════════════════════════
    // File operations
    // ══════════════════════════════════════════════════════════════════════

    private void handleUpload() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Files to Share");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Files", "*.*"),
            new FileChooser.ExtensionFilter("Images",    "*.png", "*.jpg", "*.jpeg", "*.gif"),
            new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt"));
        List<File> chosen = fc.showOpenMultipleDialog(primaryStage);
        if (chosen == null || chosen.isEmpty()) return;
        int copied = 0;
        for (File f : chosen) {
            try {
                File dest = new File(uploadFolder, f.getName());
                if (!dest.getCanonicalPath().startsWith(uploadFolder.getCanonicalPath())) {
                    AppLogger.info("Blocked path traversal: " + f.getName()); continue;
                }
                Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException ex) { AppLogger.error("Upload failed: " + f.getName(), ex); }
        }
        if (copied > 0) { refreshList(); broadcast(); }
    }

    private void removeSelected() {
        for (String i : new ArrayList<>(fileList.getSelectionModel().getSelectedItems())) {
            int lp = i.lastIndexOf(" (");
            String fname = lp >= 0 ? i.substring(0, lp).trim() : i.trim();
            new File(uploadFolder, fname).delete();
        }
        refreshList(); broadcast();
    }

    private void clearAll()  { deleteAll(); refreshList(); broadcast(); }

    private void deleteAll() {
        File[] fs = uploadFolder.listFiles(File::isFile);
        if (fs != null) for (File f : fs) f.delete();
    }

    private void broadcast() {
        if (powerOn && sessionManager != null) sessionManager.broadcastFilesChanged();
    }

    private void refreshList() {
        File[] fs = uploadFolder.listFiles(File::isFile);
        fileList.getItems().clear();
        if (fs != null) {
            Arrays.sort(fs, Comparator.comparing(f -> f.getName().toLowerCase()));
            for (File f : fs)
                fileList.getItems().add(f.getName() + " (" + fmt(f.length()) + ")");
        }
        int cnt = fs != null ? fs.length : 0;
        if (countBadge != null) countBadge.setText(cnt + (cnt == 1 ? " file" : " files"));
    }

    private String fmt(long b) {
        if (b < 1_024)         return b + " B";
        if (b < 1_048_576)     return String.format("%.1f KB", b / 1_024.0);
        if (b < 1_073_741_824) return String.format("%.1f MB", b / 1_048_576.0);
        return String.format("%.1f GB", b / 1_073_741_824.0);
    }

    private void confirmThenRun(String title, String body, Runnable action) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title); alert.setHeaderText(title); alert.setContentText(body);
        alert.initOwner(primaryStage);
        alert.showAndWait().ifPresent(btn -> { if (btn == ButtonType.OK) action.run(); });
    }
}
