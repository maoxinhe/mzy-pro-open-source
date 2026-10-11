/*
 * 梦之韵整合包自动下载页面
 * API: https://functions.liminalily.com/mc/mzy?download -> {link, size, type}
 * 下载: 调用 mzy_downloader.exe（Go 编译，支持 HTTP/3 QUIC，国内访问 Cloudflare 必须）
 */
package org.jackhuang.hmcl.ui.download;

import com.jfoenix.controls.JFXButton;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class MzyPackPage extends StackPane implements DecoratorPage {

    private final ReadOnlyObjectWrapper<DecoratorPage.State> state = new ReadOnlyObjectWrapper<>(DecoratorPage.State.fromTitle("梦之韵整合包", -1));

    private static final String MZY_API = "https://functions.liminalily.com/mc/mzy?download";
    private static final String DOWNLOADER_URL = "https://github.com/maoxinhe/mzy-pro-source/releases/latest/download/mzy_downloader.exe";
    private static final String MODLIST_API = "https://apc.camzy.uno/api/modlist";
    private static final String GAME_API = "https://apc.camzy.uno/api/modlist/game";

    // ---- 元信息 ----
    private final StringProperty packName = new SimpleStringProperty(this, "packName", "梦之韵整合包");
    private final StringProperty packType = new SimpleStringProperty(this, "packType", "—");
    private final StringProperty packSize = new SimpleStringProperty(this, "packSize", "—");
    private final StringProperty packLink = new SimpleStringProperty(this, "packLink", "");

    // ---- 下载状态 ----
    private final DoubleProperty progress = new SimpleDoubleProperty(this, "progress", -1);
    private final StringProperty statusText = new SimpleStringProperty(this, "statusText", "正在获取整合包信息…");

    private final Label nameLabel = new Label();
    private final Label typeLabel = new Label();
    private final Label sizeLabel = new Label();
    private final Label statusLabel = new Label();
    private final JFXButton downloadButton = FXUtils.newRaisedButton("一键下载");
    private final ProgressBar progressBar = new ProgressBar(-1);

    // ---- 模组列表 / 依赖清单（自建 API）----
    private final JFXButton modsButton = FXUtils.newRaisedButton("📦 整合包模组");
    private final JFXButton depsButton = FXUtils.newRaisedButton("🔗 依赖库清单");
    private final Label listTitle = new Label();
    private final VBox listBox = new VBox(6);
    private final ScrollPane listScroll = new ScrollPane(listBox);
    private String listMode = "";   // "mods" | "game"

    private Process downloadProcess;
    private Path progressFile;
    private Path targetZip;
    private long expectedSize = -1;
    private final Timeline progressTimeline;
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    @Override
    public ReadOnlyObjectProperty<DecoratorPage.State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public MzyPackPage() {
        getStyleClass().add("gray-background");

        Label title = new Label("梦之韵整合包");
        title.getStyleClass().add("h1");
        Label subtitle = new Label("官方整合包，一键下载安装，包含完整游戏内容");
        subtitle.getStyleClass().add("subtitle");

        nameLabel.getStyleClass().addAll("label-item");
        typeLabel.getStyleClass().addAll("label-item");
        sizeLabel.getStyleClass().addAll("label-item");

        // 关键：label 必须绑定数据属性，否则 API 取到的类型/大小不会显示
        nameLabel.textProperty().bind(packName);
        typeLabel.textProperty().bind(packType);
        sizeLabel.textProperty().bind(packSize);

        progressBar.setPrefWidth(420);
        progressBar.setMaxWidth(420);
        progressBar.setVisible(false);

        statusLabel.getStyleClass().add("subtitle");
        statusLabel.setWrapText(true);

        downloadButton.setOnAction(e -> startDownload());
        FXUtils.setLimitWidth(downloadButton, 220);

        HBox infoRow1 = new HBox(24, infoCell("类型", typeLabel), infoCell("大小", sizeLabel));
        infoRow1.setAlignment(Pos.CENTER_LEFT);

        // ---- 模组列表 / 依赖清单 ----
        modsButton.setOnAction(e -> loadMods());
        depsButton.setOnAction(e -> loadGame());
        FXUtils.setLimitWidth(modsButton, 200);
        FXUtils.setLimitWidth(depsButton, 200);
        HBox infoRow2 = new HBox(12, modsButton, depsButton);
        infoRow2.setAlignment(Pos.CENTER_LEFT);

        listTitle.getStyleClass().add("subtitle");
        listScroll.setPrefHeight(220);
        listScroll.setMaxHeight(220);
        listScroll.setFitToWidth(true);
        listScroll.setVisible(false);
        listScroll.setManaged(false);

        VBox content = new VBox(20, title, subtitle, infoRow1, infoRow2, listTitle, listScroll, downloadButton, progressBar, statusLabel);
        content.setAlignment(Pos.TOP_LEFT);
        content.setPadding(new Insets(32, 40, 32, 40));

        BorderPane pane = new BorderPane(content);
        pane.setPadding(new Insets(16));
        getChildren().setAll(pane);

        progressTimeline = new Timeline(new KeyFrame(Duration.millis(500), e -> pollProgress()));
        progressTimeline.setCycleCount(Timeline.INDEFINITE);

        loadMeta();
    }

    private HBox infoCell(String name, Label value) {
        Label n = new Label(name);
        n.getStyleClass().add("subtitle");
        return new HBox(8, n, value);
    }

    // ---- 模组列表 / 依赖清单 ----
    private void loadMods() {
        listMode = "mods";
        setContent("正在获取整合包模组列表…");
        listBox.getChildren().setAll(new Label("加载中…"));
        showList(true, "整合包模组列表");
        HttpRequest.GET(MODLIST_API).getJsonAsync(ModListResp.class)
                .whenComplete((resp, e) -> FXUtils.runInFX(() -> {
                    if (e != null || resp == null || !resp.ok) {
                        listBox.getChildren().setAll(new Label("模组列表获取失败：" + (e == null ? "未知错误" : e.getMessage())));
                        setContent("模组列表获取失败，请稍后重试。");
                        return;
                    }
                    List<ModItem> mods = resp.mods;
                    listBox.getChildren().clear();
                    int deleted = 0;
                    for (ModItem m : mods) {
                        Label row = new Label(m.file);
                        row.setWrapText(true);
                        if (m.delete) {
                            deleted++;
                            row.setText("🗑 " + m.file + "  （更新后将移除）");
                        }
                        listBox.getChildren().add(row);
                    }
                    showList(true, "整合包模组列表（" + mods.size() + " 个 · " + (deleted > 0 ? deleted + " 个待移除" : "无移除项") + "）");
                    setContent("整合包共 " + mods.size() + " 个模组" + (deleted > 0 ? "，" + deleted + " 个将在下次更新移除" : "") + "。");
                }));
    }

    private void loadGame() {
        listMode = "game";
        setContent("正在获取整合包依赖库清单…");
        listBox.getChildren().setAll(new Label("加载中…"));
        showList(true, "整合包依赖库清单");
        HttpRequest.GET(GAME_API).getJsonAsync(GameResp.class)
                .whenComplete((resp, e) -> FXUtils.runInFX(() -> {
                    if (e != null || resp == null || !resp.ok) {
                        listBox.getChildren().setAll(new Label("依赖清单获取失败：" + (e == null ? "未知错误" : e.getMessage())));
                        setContent("依赖清单获取失败，请稍后重试。");
                        return;
                    }
                    List<LibItem> libs = resp.libraries;
                    listBox.getChildren().clear();
                    for (LibItem lib : libs) {
                        Label row = new Label();
                        row.setWrapText(true);
                        if (lib.source != null && !lib.source.isEmpty()) {
                            row.setText("• " + lib.path + "\n    ↳ " + lib.source);
                        } else {
                            row.setText("• " + lib.path);
                        }
                        listBox.getChildren().add(row);
                    }
                    showList(true, "整合包依赖库（" + libs.size() + " 个 · MC " + (resp.mcVersion == null ? "—" : resp.mcVersion) + "）");
                    setContent("整合包依赖库共 " + libs.size() + " 个（已含权威下载源）。");
                }));
    }

    private void showList(boolean visible, String title) {
        listScroll.setVisible(visible);
        listScroll.setManaged(visible);
        if (visible) listTitle.setText(title);
    }

    private void setContent(String s) {
        statusText.set(s);
        statusLabel.setText(s);
    }

    // ---- 元信息获取 ----
    private void loadMeta() {
        LOG.info("Fetching modpack metadata from " + MZY_API);
        HttpRequest.GET(MZY_API).getJsonAsync(MzyPackMeta.class)
                .whenComplete((meta, e) -> FXUtils.runInFX(() -> {
                    if (e != null) {
                        LOG.warning("Failed to fetch modpack metadata", e);
                        setContent("获取整合包信息失败：" + e.getMessage() + "\n点击按钮可重试下载。");
                        downloadButton.setDisable(false);
                        return;
                    }
                    LOG.info("Modpack metadata: " + meta.link + " / " + meta.size + " bytes / " + meta.type);
                    packLink.set(meta.link);
                    expectedSize = meta.size;
                    packType.set(meta.type);
                    packSize.set(formatSize(meta.size));
                    if (meta.link.contains("/")) {
                        String file = meta.link.substring(meta.link.lastIndexOf('/') + 1);
                        packName.set("梦之韵整合包 " + file.replace(".zip", ""));
                    }
                    setContent("整合包就绪，点击「一键下载」开始（需支持 HTTP/3 快速通道）。");
                }));
    }

    // ---- 下载 ----
    private void startDownload() {
        if (downloading.get()) return;
        Path downloadDir = chooseDownloadDir();
        try {
            Files.createDirectories(downloadDir);
        } catch (IOException e) {
            Controllers.dialog("无法创建下载目录：" + downloadDir, "梦之韵整合包", MessageDialogPane.MessageType.ERROR);
            return;
        }

        String link = packLink.get();
        if (link.isEmpty()) {
            setContent("尚未获取到下载链接，正在重试…");
            loadMeta();
            return;
        }

        String fileName = "梦之韵整合包.zip";
        if (link.contains("/")) {
            String f = link.substring(link.lastIndexOf('/') + 1);
            if (!f.isEmpty()) fileName = f;
        }
        targetZip = downloadDir.resolve(fileName);

        // 已有完整文件则直接安装
        if (Files.exists(targetZip)) {
            try {
                if (expectedSize > 0 && Files.size(targetZip) == expectedSize) {
                    Controllers.confirm("检测到已下载的整合包（" + packSize.get() + "），是否立即安装？",
                            "梦之韵整合包", this::installModpack, () -> {});
                    return;
                }
            } catch (IOException ignored) {
            }
            try { Files.delete(targetZip); } catch (IOException ignored) { }
        }

        ensureDownloaderAndRun(downloadDir);
    }

    private Path chooseDownloadDir() {
        // 优先启动器所在目录/downloads，避免写入系统盘 AppData
        try {
            Path cwd = Metadata.CURRENT_DIRECTORY.resolve("downloads");
            if (Files.isWritable(cwd.getParent() == null ? cwd : cwd.getParent())) {
                return cwd;
            }
        } catch (Exception ignored) {
        }
        return Metadata.HMCL_LOCAL_HOME.resolve("downloads");
    }

    private void ensureDownloaderAndRun(Path downloadDir) {
        Path downloader = downloadDir.resolve(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "mzy_downloader.exe" : "mzy_downloader");
        if (!Files.exists(downloader)) {
            setContent("首次使用需要下载高速下载组件（HTTP/3，约 7MB）…");
            downloadButton.setDisable(true);
            Controllers.taskDialog(new FileDownloadTask(DOWNLOADER_URL, downloader)
                            .whenComplete(Schedulers.javafx(), e -> {
                                if (e == null) {
                                    runDownloader(downloader);
                                } else {
                                    downloadButton.setDisable(false);
                                    setContent("高速下载组件下载失败：" + e.getMessage());
                                    Controllers.dialog("高速下载组件下载失败：" + e.getMessage(),
                                            "梦之韵整合包", MessageDialogPane.MessageType.ERROR);
                                }
                            }),
                    "下载高速组件", TaskCancellationAction.NORMAL);
        } else {
            runDownloader(downloader);
        }
    }

    private void runDownloader(Path downloader) {
        progressFile = targetZip.resolveSibling(targetZip.getFileName() + ".progress.json");
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        downloadButton.setDisable(true);
        downloadButton.setText("下载中…");
        setContent("正在通过 HTTP/3 高速通道下载整合包…");

        Path out = targetZip;
        try {
            Files.deleteIfExists(progressFile);
        } catch (IOException ignored) {
        }

        ProcessBuilder pb = new ProcessBuilder(
                downloader.toString(),
                "-url", packLink.get(),
                "-out", out.toString(),
                "-progress", progressFile.toString(),
                "-threads", "8",
                "-expected", String.valueOf(expectedSize));
        pb.redirectErrorStream(true);
        try {
            downloadProcess = pb.start();
            downloading.set(true);
            progressTimeline.play();
            // 后台等待进程结束
            Thread t = new Thread(() -> {
                try {
                    int code = downloadProcess.waitFor();
                    FXUtils.runInFX(() -> onProcessExit(code));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            t.setDaemon(true);
            t.setName("mzy-download-watcher");
            t.start();
        } catch (IOException e) {
            downloading.set(false);
            downloadButton.setDisable(false);
            downloadButton.setText("一键下载");
            progressBar.setVisible(false);
            setContent("启动下载进程失败：" + e.getMessage());
            Controllers.dialog("启动下载进程失败：" + e.getMessage(), "梦之韵整合包", MessageDialogPane.MessageType.ERROR);
        }
    }

    private void pollProgress() {
        if (!downloading.get()) return;
        try {
            if (progressFile == null || !Files.exists(progressFile)) return;
            String json = new String(Files.readAllBytes(progressFile), StandardCharsets.UTF_8);
            DownloadProgress p = JsonUtils.fromNonNullJson(json, DownloadProgress.class);
            if ("downloading".equals(p.state)) {
                progressBar.setProgress(p.total > 0 ? (double) p.downloaded / p.total : 0);
                double mb = p.downloaded / 1048576.0;
                double totalMb = p.total / 1048576.0;
                double speedMb = p.speed / 1048576.0;
                setContent(String.format("正在下载：%.1f MB / %.1f MB ｜ %.1f MB/s", mb, totalMb, speedMb));
            } else if ("done".equals(p.state)) {
                progressBar.setProgress(1);
                setContent("下载完成！");
            }
        } catch (Exception ignored) {
        }
    }

    private void onProcessExit(int code) {
        progressTimeline.stop();
        downloading.set(false);
        progressBar.setVisible(false);
        if (code == 0) {
            try {
                if (expectedSize > 0 && Files.size(targetZip) == expectedSize) {
                    downloadButton.setText("重新下载");
                    setContent("整合包下载完成（" + packSize.get() + "）。");
                    Controllers.confirm("整合包下载完成（" + packSize.get() + "），是否立即安装？",
                            "梦之韵整合包", this::installModpack, () -> {});
                    return;
                }
            } catch (IOException ignored) {
            }
            setContent("下载完成，但文件校验未通过，请重新下载。");
            Controllers.dialog("文件大小校验未通过，请重新下载。", "梦之韵整合包", MessageDialogPane.MessageType.ERROR);
        } else {
            setContent("下载失败，请重试。");
            Controllers.dialog("下载失败，请检查网络后重试。", "梦之韵整合包", MessageDialogPane.MessageType.ERROR);
        }
        downloadButton.setDisable(false);
    }

    private void installModpack() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (!repository.isLoaded()) {
            Controllers.dialog("游戏库尚未就绪，请稍后再试。", "梦之韵整合包", MessageDialogPane.MessageType.WARNING);
            return;
        }
        Controllers.getDecorator().startWizard(new ModpackInstallWizardProvider(repository, targetZip), "安装梦之韵整合包");
    }

    private static String formatSize(long bytes) {
        double mb = bytes / 1048576.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024);
    }

    // ---- 元信息 DTO ----
    public static class MzyPackMeta {
        public String link;
        public long size;
        public String type;
    }

    // ---- 模组列表 DTO ----
    public static class ModListResp {
        public boolean ok;
        public String version;
        public int total;
        public List<ModItem> mods;
    }

    public static class ModItem {
        public String file;
        public boolean delete;
    }

    // ---- 依赖清单 DTO ----
    public static class GameResp {
        public boolean ok;
        public String mcVersion;
        public int totalLibs;
        public List<LibItem> libraries;
    }

    public static class LibItem {
        public String path;
        public String url;
        public String source;
    }

    // ---- 进度 DTO ----
    public static class DownloadProgress {
        public String state;
        public long downloaded;
        public long total;
        public long speed;
        public String error;
    }
}
