package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import com.longfor.lmk.k8slogviewer.utils.KubectlLogFetcherUtil;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.longfor.lmk.k8slogviewer.config.AppConfig.initializeEnvironment;
import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;
import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showConfirm;

public class K8sLogViewerController {
    private static final Logger log = LoggerFactory.getLogger(K8sLogViewerController.class);

    @FXML
    public ProgressIndicator loadingIndicator;
    @FXML
    public AnchorPane logAreaContainer;
    @FXML
    public CodeArea headerArea;
    @FXML
    private TreeView<String> treeView;
    @FXML
    private CodeArea logArea;
    @FXML
    private TextField searchField;
    @FXML
    private Button refreshButton;
    @FXML
    private Button settingsButton;
    @FXML
    private TextField logSearchField;
    @FXML
    private TextField contextField; // 改为 TextField
    @FXML
    private TextField tailField; // 改为 TextField
    @FXML
    private Button searchToggleButton; // 控制定时搜索
    @FXML
    private Button timeRangeButton;
    @FXML
    private Button searchButton;

    // 内联搜索栏相关
    @FXML
    private HBox searchBar;
    @FXML
    private TextField inlineSearchField;
    private int lastSearchIndex = -1;
    private String lastKeyword = "";
    private final List<int[]> matchPositions = new ArrayList<>(); // 保存所有匹配起止位置
    private int currentMatchIndex = 0;         // 当前高亮匹配编号

    @FXML
    public void initialize() throws IOException {
        log.info("k8s 日志查看 初始化...");
        // 异步检查 bash
        Platform.runLater(() -> {
            // 初始化配置文件
            boolean b = initializeEnvironment();
            if (!b) {
                log.warn("初始化失败，手动设置配置文件...");
                // 弹出设置窗口，让用户配置 Git Bash 路径与 kubeconfig 路径
                try {
                    SettingsController settingDialog = new SettingsController();
                    settingDialog.openSettingsDialog();
                } catch (IOException e) {
                    showAlert("初始化失败", "请联系邮箱 <lmk62023@outlook.com> 解决，或自己调试解决！");
                }
            }
        });

        // 设置 左侧设置图标
        try {
            ImageView icon = new ImageView(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))));
            icon.setFitHeight(20);
            icon.setFitWidth(20);
            settingsButton.setGraphic(icon);
        } catch (Exception e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置图标: " + e.getMessage()));
        }

        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        k8sQuery.setContextLines(0);

        tailField.setPromptText("尾行数");
        tailField.setText(String.valueOf(1000));
        k8sQuery.setTailLines(1000);

        searchButton.setText("搜索");
        searchButton.getStyleClass().add("action-button");
        //初始化日志样式
        // 初始化 CodeArea
        headerArea.setEditable(false);
        logArea.setEditable(false);
        // 设置日志显示行号
        IntFunction<Node> numberFactory = line -> {
            Label label = new Label(String.valueOf(line + 1)); // 可选 +1，让行号从 1 开始
            label.getStyleClass().add("line-number");
            return label;
        };
        logArea.setParagraphGraphicFactory(numberFactory); // 添加行号

        headerArea.setWrapText(true);
        logArea.setWrapText(true); // 可选：自动换行
        // 设置滚动条
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(logArea);
        AnchorPane.setTopAnchor(scrollPane, 30.0); // 给搜索栏留空间
        AnchorPane.setBottomAnchor(scrollPane, 0.0);
        AnchorPane.setLeftAnchor(scrollPane, 0.0);
        AnchorPane.setRightAnchor(scrollPane, 0.0);

        logAreaContainer.getChildren().add(scrollPane);

        // 初始化内联搜索栏
        initSearchBar();

        // 上下文行数监听器 校验
        contextField.textProperty().addListener((obs, oldVal, newVal) -> {
            newVal = newVal.isEmpty() ? "0" : newVal;
            if (!newVal.matches("\\d*") || Integer.parseInt(newVal) < 0) {
                contextField.setText(oldVal);
                Platform.runLater(() -> showAlert("错误", "请输入大于等于0的数字"));
            } else {
                int num = Integer.parseInt(newVal);
                k8sQuery.setFollow(num == 0);
                k8sQuery.setContextLines(num);
            }
        });

        // 截取日志多少行监听
        tailField.textProperty().addListener((obs, oldVal, newVal) -> {
            newVal = newVal.isEmpty() ? "0" : newVal;
            if (!newVal.matches("\\d*") || Integer.parseInt(newVal) < 0) {
                tailField.setText(oldVal);
                Platform.runLater(() -> showAlert("错误", "请输入大于等于0的数字"));
            } else {
                int num = Integer.parseInt(newVal);
                if (num != 0) {
                    k8sQuery.setTailLines(num);
                    return;
                }
                // 输入为0时，弹出确认提示
                Platform.runLater(() -> showConfirm("提示", "尾行数为 0|null 会非常卡，是否继续？",
                        () -> k8sQuery.setTailLines(0),
                        () -> tailField.setText(oldVal)));
            }
        });

        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshTree(newVal));

        timeRangeButton.setOnAction(e -> showTimeRangeDialog());
        // 设置树视图单击事件
        treeView.setOnMouseClicked(this::handleTreeViewClick);

        // 树节点选择事件
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getParent() != null) {
                TreeItem<String> parent = newVal.getParent();
                if (parent.getParent() != null) {
                    // parent 是 namespace，newVal 是 pod
                    String namespace = parent.getValue();
                    String podName = newVal.getValue();

                    log.info("选择命名空间: {}, Pod: {}", namespace, podName);
                    k8sQuery.setNamespace(namespace);
                    k8sQuery.setPodName(podName);
                    String text = logSearchField.getText();
                    if (text != null && !text.isEmpty()) {
                        k8sQuery.setKeyword(text);
                    }
                    showLogs();
                }
            }
        });

        // 初始加载树
        refreshTree(null);
    }

    @FXML
    private Label matchCountLabel;

    /**
     * 初始化内联搜索栏
     */
    private void initSearchBar() {
        inlineSearchField = new TextField();
        inlineSearchField.setPromptText("查找...");
        HBox.setHgrow(inlineSearchField, Priority.ALWAYS);

        Button prevBtn = new Button("上一处");
        prevBtn.setOnAction(e -> findPrev());

        Button nextBtn = new Button("下一处");
        nextBtn.setOnAction(e -> findNext());

        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(e -> closeSearch());

        searchBar = new HBox(5, inlineSearchField, prevBtn, nextBtn, closeBtn);
        searchBar.setPadding(new Insets(5));
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setVisible(false);
        searchBar.setStyle("-fx-background-color: #e8e8e8;");

        matchCountLabel = new Label("0/0");
        matchCountLabel.setStyle("-fx-font-weight: bold;");

        searchBar = new HBox(5, inlineSearchField, prevBtn, nextBtn, matchCountLabel, closeBtn);
        searchBar.setPadding(new Insets(5));
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setVisible(false);
        searchBar.setStyle("-fx-background-color: #e8e8e8;");

        AnchorPane.setTopAnchor(searchBar, 0.0);
        AnchorPane.setLeftAnchor(searchBar, 0.0);
        AnchorPane.setRightAnchor(searchBar, 0.0);

        logAreaContainer.getChildren().add(searchBar);

        // 绑定快捷键 Ctrl+F 打开，ESC 关闭
        logArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN).match(event)) {
                toggleSearchBar(true);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && searchBar.isVisible()) {
                toggleSearchBar(false);
                event.consume();
            }
        });

        inlineSearchField.setOnAction(e -> findNext());
    }

    // ----------------- 内联搜索逻辑 -----------------
    @FXML
    private void findNext() {
        String keyword = inlineSearchField.getText();
        if (keyword == null || keyword.isEmpty()) return;

        computeAllMatches(keyword);

        if (matchPositions.isEmpty()) return;

        currentMatchIndex = (currentMatchIndex + 1) % matchPositions.size();
        selectCurrentMatch();
    }

    @FXML
    private void findPrev() {
        String keyword = inlineSearchField.getText();
        if (keyword == null || keyword.isEmpty()) return;

        computeAllMatches(keyword);

        if (matchPositions.isEmpty()) return;

        currentMatchIndex = (currentMatchIndex - 1 + matchPositions.size()) % matchPositions.size();
        selectCurrentMatch();
    }

    @FXML
    private void closeSearch() {
        toggleSearchBar(false);
    }

    private void toggleSearchBar(boolean show) {
        searchBar.setVisible(show);
        if (show) {
            inlineSearchField.requestFocus();
            inlineSearchField.selectAll();
        } else {
            logArea.clearStyle(0, logArea.getLength());
            lastSearchIndex = -1;
            lastKeyword = "";
            matchPositions.clear();
            currentMatchIndex = 0;
        }
    }

    // ----------------- 计算所有匹配 -----------------
    private void computeAllMatches(String keyword) {
        if (!keyword.equals(lastKeyword)) {
            lastKeyword = keyword;
            matchPositions.clear();

            String text = logArea.getText();
            Matcher matcher = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE).matcher(text);
            while (matcher.find()) matchPositions.add(new int[]{matcher.start(), matcher.end()});
        }
        highlightAllMatches(keyword);
        updateMatchLabel();
    }

    // ----------------- 高亮匹配 -----------------
    private void highlightAllMatches(String keyword) {
        String text = logArea.getText();
        logArea.setStyleSpans(0, LogStyleUtil.computeHighlighting(false, text, keyword));
    }

    // ----------------- 选择当前匹配 -----------------
    private void selectCurrentMatch() {
        if (matchPositions.isEmpty()) return;
        int[] pos = matchPositions.get(currentMatchIndex);
        logArea.selectRange(pos[0], pos[1]);
        logArea.requestFollowCaret();
        updateMatchLabel();
    }

    // ----------------- 更新匹配数量显示 -----------------
    private void updateMatchLabel() {
        if (matchPositions.isEmpty()) {
            matchCountLabel.setText("0/0");
        } else {
            matchCountLabel.setText((currentMatchIndex + 1) + "/" + matchPositions.size());
        }
    }

    /**
     * 增量刷新匹配（不会清空原有样式）
     */
    private void refreshMatchesIncremental() {
        String keyword = inlineSearchField.getText();
        if (keyword == null || keyword.isEmpty()) return;

        // 新增的文本
        String text = logArea.getText();
        Matcher matcher = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE).matcher(text);

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            // 只添加新的匹配位置
            if (matchPositions.isEmpty() || start > matchPositions.get(matchPositions.size() - 1)[1]) {
                matchPositions.add(new int[]{start, end});
            }
        }

        // 高亮所有匹配
        highlightAllMatches(keyword);

        // 保持 currentMatchIndex 不变或者更新
        if (!matchPositions.isEmpty() && currentMatchIndex >= matchPositions.size()) {
            currentMatchIndex = matchPositions.size() - 1;
        }
        updateMatchLabel();
    }

    private void showLogs() {
        try {
            K8sQuery query = AppConfig.getK8sQuery();
            query.setHeaderCaptured(true);
            query.setCodeAreas(Arrays.asList(logArea, headerArea));
            KubectlLogFetcherUtil.fetchStreaming("/scripts/search_logs.sh",
                    line -> {
                        LogStyleUtil.appendHighlightedLine(headerArea, logArea, line);
                        // 增量刷新匹配
                        Platform.runLater(this::refreshMatchesIncremental);
                    });
        } catch (IOException e) {
            log.error("获取日志失败: {}", e.getMessage());
            Platform.runLater(() -> showAlert("错误", "无法获取日志: " + e.getMessage()));
        }
    }

    private void refreshTree(String filter) {
        TreeItem<String> rootItem = AppConfig.getRootItem();
        if (filter == null || filter.isEmpty()) {
            setTreeRoot(rootItem);
            return;
        }
        TreeItem<String> treeItem = CommonUtils.filterTree(rootItem, filter);
        setTreeRoot(treeItem);
    }

    private void setTreeRoot(TreeItem<String> rootItem) {
        Platform.runLater(() -> treeView.setRoot(rootItem));
    }

    // 处理树视图单击事件
    private void handleTreeViewClick(MouseEvent event) {
        if (event.getClickCount() == 1) {
            TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                selectedItem.setExpanded(!selectedItem.isExpanded());
            }
        }
    }

    @FXML
    public void searchButtonClick(MouseEvent mouseEvent) {
        AppConfig.getK8sQuery().setKeyword(logSearchField.getText());
        showLogs();
    }

    @FXML
    public void handleOpenSettings(MouseEvent mouseEvent) {
        SettingsController settingDialog = new SettingsController();
        try {
            settingDialog.openSettingsDialog();
        } catch (IOException e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置窗口: " + e.getMessage()));
        }
    }

    @FXML
    public void refreshOnClick(MouseEvent mouseEvent) {
        // 禁用按钮并显示“加载中...”
        refreshButton.setDisable(true);
        loadingIndicator.setVisible(true);

        new Thread(() -> {
            try {
                // 执行刷新逻辑
                AppConfig.clearRootItem();
                refreshTree(searchField.getText());
            } catch (Exception e) {
                log.error("刷新失败: {}", e.getMessage());
            }
            Platform.runLater(() -> {
                refreshButton.setDisable(false);
                loadingIndicator.setVisible(false);
            });
        }).start();
    }

    private void showTimeRangeDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("选择日志起始时间");
        dialog.getIcons().add(new Image(
                Objects.requireNonNull(getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))
        ));

        VBox dialogPane = new VBox(15);
        dialogPane.setPadding(new Insets(20));
        dialogPane.getStyleClass().add("dialog-pane");

        Label instruction = new Label("从哪天开始查看日志（直到现在）:");
        instruction.getStyleClass().add("label-tab");
        // 默认今天
        DatePicker startDate = new DatePicker(LocalDate.now());

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        Button cancelButton = new Button("取消");
        cancelButton.getStyleClass().add("action-button");
        cancelButton.setOnAction(e -> dialog.close());

        Button applyButton = new Button("应用");
        applyButton.getStyleClass().add("action-button");

        applyButton.setOnAction(e -> {
            LocalDate date = startDate.getValue();
            if (date != null) {
                LocalDateTime start = date.atStartOfDay();
                long seconds = Duration.between(start, LocalDateTime.now()).getSeconds();
                if (seconds < 0) {
                    showAlert("错误", "选择的时间不能晚于现在");
                    return;
                }
                AppConfig.getK8sQuery().setSinceSeconds(seconds);
                showLogs();
                dialog.close();
            } else {
                showAlert("错误", "请选择开始日期");
            }
        });

        buttonBox.getChildren().addAll(applyButton, cancelButton);
        dialogPane.getChildren().addAll(instruction, startDate, buttonBox);

        Scene dialogScene = new Scene(dialogPane, Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        dialogScene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());

        dialog.setScene(dialogScene);
        dialog.setResizable(false);
        dialog.showAndWait();
    }

    @FXML
    public void searchToggleClick(MouseEvent mouseEvent) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        boolean searchRunning = !k8sQuery.isSearchRunning();
        k8sQuery.setSearchRunning(searchRunning);
        // 暂停滚动控制
        searchToggleButton.setText(searchRunning ? "暂停" : "恢复");
    }
}
