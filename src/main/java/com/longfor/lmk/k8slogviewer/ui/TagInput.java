package com.longfor.lmk.k8slogviewer.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 命名空间选择器。
 * 输入框固定高度单行：左侧已选标签(chips)横向排列(超宽隐藏)，
 * 右侧蓝色 "+" 按钮点击弹出 Dialog 弹窗（含搜索 + CheckBox 列表）。
 */
public class TagInput extends HBox {

    private static final Logger log = LoggerFactory.getLogger(TagInput.class);

    private final ObservableList<String> allItems = FXCollections.observableArrayList();
    private final ObservableList<String> selectedItems = FXCollections.observableArrayList();

    /* ---- 输入框本体 ---- */
    private final StackPane tagClipPane = new StackPane();
    private final HBox tagRow = new HBox(4);
    private Label moreLabel;                          // "+N"
    private final Button addBtn = new Button("+");     // 蓝色加号

    /* ---- Dialog 内部引用（由 FXML 注入） ---- */
    private TextField dialogSearchField;
    private ListView<String> dialogListView;
    private FilteredList<String> filteredItems;

    private Consumer<List<String>> onSelectionChanged;
    private Runnable onRefreshNamespaces;

    public TagInput() {
        getStyleClass().add("tag-input");
        buildSelf();
        bindEvents();
    }

    // ==================== 构建 ====================

    private void buildSelf() {
        setAlignment(Pos.CENTER_LEFT);

        // 标签行 — 单行横向排列，超宽 clip 隐藏
        tagRow.setAlignment(Pos.CENTER_LEFT);
        tagRow.getStyleClass().add("tag-row");
        tagClipPane.getChildren().add(tagRow);

        // clip 裁剪（初始设为0避免首次布局前溢出）
        Rectangle clipRect = new Rectangle(0, 0);
        tagClipPane.setClip(clipRect);
        tagClipPane.layoutBoundsProperty().addListener((obs, old, b) -> {
            double w = Math.max(0, b.getWidth());
            double h = Math.max(0, b.getHeight());
            clipRect.setWidth(w);
            clipRect.setHeight(h);
        });

        // "+N" 指示器
        moreLabel = new Label("");
        moreLabel.setVisible(false);
        moreLabel.setManaged(false);
        moreLabel.getStyleClass().add("tag-more-label");
        Tooltip tip = new Tooltip();
        tip.getStyleClass().add("tag-more-tooltip");
        moreLabel.setTooltip(tip);

        // 蓝色加号按钮
        addBtn.setFocusTraversable(false);
        addBtn.getStyleClass().add("add-btn");

        HBox.setHgrow(tagClipPane, Priority.ALWAYS);
        getChildren().addAll(tagClipPane, moreLabel, addBtn);
    }

    private void bindEvents() {
        addBtn.setOnAction(e -> openSelectorDialog());

        selectedItems.addListener((ListChangeListener<String>) c -> {
            if (!suppressRebuild) rebuildTags();
        });
        tagClipPane.widthProperty().addListener((obs, o, n) ->
                Platform.runLater(this::updateOverflow));
    }

    // ==================== 公共 API ====================

    public ObservableList<String> getItems() { return allItems; }
    public ObservableList<String> getSelectedItems() { return selectedItems; }

    public void setOnSelectionChanged(Consumer<List<String>> handler) { this.onSelectionChanged = handler; }

    /** 设置命名空间刷新回调（弹窗中点击 ⟳ 按钮时触发） */
    public void setOnRefreshNamespaces(Runnable callback) { this.onRefreshNamespaces = callback; }

    public void clearSelection() { selectedItems.clear(); }

    private boolean suppressRebuild = false;

    /** 静默清空（不触发 rebuild，用于切换配置等场景） */
    public void resetSilent() {
        suppressRebuild = true;
        allItems.clear();
        selectedItems.clear();
        suppressRebuild = false;
    }

    /** 批量选中（中间不触发 rebuild，结束时只重建一次） */
    public void selectMultiple(List<String> items) {
        suppressRebuild = true;
        for (String item : items) {
            if (!selectedItems.contains(item) && allItems.contains(item)) {
                selectedItems.add(item);
            }
        }
        suppressRebuild = false;
        rebuildTags();
    }

    public void select(String item) {
        if (!selectedItems.contains(item) && allItems.contains(item))
            selectedItems.add(item);
    }

    public void deselect(String item) { selectedItems.remove(item); }

    // ==================== Dialog 弹窗 ====================

    /** 点击 + 按钮打开命名空间选择弹窗 */
    private void openSelectorDialog() {
        try {
            URL url = getClass().getResource("/com/longfor/lmk/k8slogviewer/ns_selector_dialog.fxml");
            // 弹窗内使用独立副本，不直接影响外部 selectedItems
            ObservableList<String> tempSelected = FXCollections.observableArrayList(selectedItems);
            FXMLLoader loader = new FXMLLoader(url);
            loader.setController(new NsDialogController(allItems, tempSelected));
            DialogPane dialogPane = loader.load();
            NsDialogController dialogCtrl = (NsDialogController) loader.getController();
            dialogCtrl.setOnRefresh(onRefreshNamespaces);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("选择命名空间");
            dialog.setDialogPane(dialogPane);
            dialog.setResizable(true);
            dialog.getDialogPane().setMinSize(420, 380);

            // 绑定到主窗口（避免任务栏独立显示）
            if (getScene() != null && getScene().getWindow() != null) {
                dialog.initOwner(getScene().getWindow());
                Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
                Image icon = new Image(Objects.requireNonNull(
                        getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/k8s.png")));
                stage.getIcons().add(icon);
                stage.setMinWidth(440);
                stage.setMinHeight(420);
            }

            String css = Objects.requireNonNull(
                    getClass().getResource("/styles.css")).toExternalForm();
            dialog.getDialogPane().getStylesheets().add(css);

            // 点"确定"才将临时选择同步到正式 selectedItems
            // 确定前校验：未选命名空间时阻止关闭弹窗并提示
            for (ButtonType bt : dialogPane.getButtonTypes()) {
                if (bt.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                    Button okBtn = (Button) dialogPane.lookupButton(bt);
                    okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                        if (tempSelected.isEmpty()) {
                            event.consume();
                            com.longfor.lmk.k8slogviewer.utils.CommonUtils.showToast(
                                    dialogPane, "✗", "请至少选择一个命名空间", "#E74C3C");
                        }
                    });
                    break;
                }
            }

            dialog.showAndWait().ifPresent(result -> {
                if (result.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                    selectedItems.setAll(tempSelected);
                    fireCallback();
                    Platform.runLater(this::rebuildTags);
                }
            });

        } catch (IOException e) {
            log.error("打开命名空间选择对话框失败", e);
        } catch (NullPointerException e) {
            log.error("加载 ns_selector_dialog.fxml 失败", e);
        }
    }

    // ==================== 标签重建 & 溢出计算 ====================

    private void fireCallback() {
        if (onSelectionChanged != null)
            onSelectionChanged.accept(new ArrayList<>(selectedItems));
    }

    private void rebuildTags() {
        List<Node> chips = new ArrayList<>();
        for (String item : selectedItems)
            chips.add(createChip(item));

        if (chips.isEmpty()) {
            Label ph = new Label("点击 + 选择命名空间");
            ph.getStyleClass().add("placeholder");
            tagRow.getChildren().setAll(ph);
            moreLabel.setVisible(false);
            moreLabel.getTooltip().setText("");
            return;
        }
        // 先放入所有 chip
        tagRow.getChildren().setAll(chips);
        // 同步计算溢出（避免闪烁）
        applyOverflow();
        // 异步再算一次（等布局完成后精确值）
        Platform.runLater(this::applyOverflow);
    }

    private void applyOverflow() {
        if (selectedItems.isEmpty()) return;
        double avail = tagClipPane.getWidth();
        // 首次布局前用 prefWidth 估算
        boolean layoutDone = avail > 0;
        if (!layoutDone) avail = 220; // 预估可用宽度

        double used = 0;
        int visible = 0, hidden = 0;
        double moreW = 22;
        double limit = avail - moreW - 4;

        for (Node child : tagRow.getChildren()) {
            double cw = layoutDone ? child.getLayoutBounds().getWidth() : child.prefWidth(-1);
            if (cw <= 0) cw = 50; // 兜底
            if (used + cw <= limit || visible == 0) {
                child.setVisible(true); child.setManaged(true);
                used += cw + 4;
                visible++;
            } else {
                child.setVisible(false); child.setManaged(false);
                hidden++;
            }
        }

        if (hidden > 0) {
            moreLabel.setText("+" + hidden);
            moreLabel.setVisible(true); moreLabel.setManaged(true);
            StringBuilder sb = new StringBuilder();
            for (int i = visible; i < selectedItems.size(); i++) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(selectedItems.get(i));
            }
            ((Tooltip)moreLabel.getTooltip()).setText(sb.toString());
        } else {
            moreLabel.setVisible(false); moreLabel.setManaged(false);
        }
    }

    private void updateOverflow() {
        applyOverflow();
    }

    private Node createChip(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("tag-chip");

        Button xBtn = new Button("×");
        xBtn.getStyleClass().add("tag-close-btn");
        xBtn.setFocusTraversable(false);
        xBtn.setOnAction(e -> removeTag(text));

        HBox hbox = new HBox(3, lbl, xBtn);
        hbox.getStyleClass().add("tag-chip-container");
        hbox.setAlignment(Pos.CENTER_LEFT);
        return hbox;
    }

    private void removeTag(String text) {
        if (selectedItems.size() <= 1) {
            Platform.runLater(() -> com.longfor.lmk.k8slogviewer.utils.CommonUtils.showToast(
                    this, "✗", "至少保留一个命名空间", "#E74C3C"));
            return;
        }
        selectedItems.remove(text);
        fireCallback();
    }

    // ==================== Dialog Controller（内部类，FXML 绑定）====================

    /**
     * 命名空间选择弹窗的 FXML Controller。
     * 左右双栏布局：左侧未选中，右侧已选中，中间移动按钮。
     */
    public static class NsDialogController {

        @FXML private TextField searchField;
        @FXML private ListView<String> leftListView;
        @FXML private ListView<String> rightListView;
        @FXML private Button selectAllBtn;
        @FXML private Button invertBtn;
        @FXML private Button deselectAllBtn;
        @FXML private Button refreshBtn;
        @FXML private Label countLabel;

        private final ObservableList<String> allItems;
        private final ObservableList<String> tempSelected;
        private FilteredList<String> leftFiltered;
        private FilteredList<String> rightFiltered;
        private ObservableList<String> leftSource;
        private Runnable onRefreshCallback;

        public NsDialogController(ObservableList<String> allItems,
                                  ObservableList<String> tempSelected) {
            this.allItems = allItems;
            this.tempSelected = tempSelected;
        }

        /** 设置刷新回调（点击 ⟳ 按钮时调用） */
        public void setOnRefresh(Runnable callback) {
            this.onRefreshCallback = callback;
        }

        @FXML
        public void initialize() {
            // 左侧：未选中的项，手动维护（响应 tempSelected / allItems 变化）
            leftSource = FXCollections.observableArrayList();
            leftFiltered = new FilteredList<>(leftSource, s -> true);
            leftListView.setItems(leftFiltered);
            leftListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            leftListView.setCellFactory(lv -> createNsCell());

            // 右侧：已选中的，同样支持搜索过滤
            rightFiltered = new FilteredList<>(tempSelected, s -> true);
            rightListView.setItems(rightFiltered);
            rightListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            rightListView.setCellFactory(lv -> createNsCell());

            // 搜索同时过滤左右两侧列表
            searchField.textProperty().addListener((obs, old, val) -> {
                String key = val.trim().toLowerCase();
                java.util.function.Predicate<String> predicate = key.isEmpty()
                        ? s -> true : s -> s.toLowerCase().contains(key);
                leftFiltered.setPredicate(predicate);
                rightFiltered.setPredicate(predicate);
            });

            // tempSelected 或 allItems 变化时刷新左侧列表
            tempSelected.addListener((javafx.collections.ListChangeListener<String>) c -> refreshLeftList());
            allItems.addListener((javafx.collections.ListChangeListener<String>) c -> refreshLeftList());
            refreshLeftList();

            searchField.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE && !searchField.getText().isEmpty()) {
                    searchField.clear();
                }
                if (e.getCode() == KeyCode.ENTER) {
                    moveSelectionRight();
                }
            });

            // 双击移动
            leftListView.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) { moveSelectionRight(); e.consume(); }
            });
            rightListView.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) { moveSelectionLeft(); e.consume(); }
            });

            // 统计监听
            tempSelected.addListener((javafx.collections.ListChangeListener<String>) c -> updateCount());
            updateCount();
        }

        /** 创建命名空间列表单元格 */
        private ListCell<String> createNsCell() {
            return new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null); setGraphic(null); return;
                    }
                    setText(item);
                    setGraphic(null);
                    getStyleClass().add("ns-list-item");
                }
            };
        }

        /** 更新底部统计文字 */
        private void updateCount() {
            int total = allItems.size();
            int sel = tempSelected.size();
            countLabel.setText("已选择 " + sel + " / 共 " + total + " 个命名空间");
        }

        /** 刷新左侧未选中列表（allItems - tempSelected） */
        private void refreshLeftList() {
            leftSource.clear();
            for (String item : allItems) {
                if (!tempSelected.contains(item)) {
                    leftSource.add(item);
                }
            }
        }

        // ==================== 移动操作 ====================

        @FXML
        public void onMoveRight() { moveSelectionRight(); }

        @FXML
        public void onMoveLeft() { moveSelectionLeft(); }

        private void moveSelectionRight() {
            var items = new ArrayList<>(leftListView.getSelectionModel().getSelectedItems());
            items.forEach(item -> {
                if (!item.isEmpty()) {
                    tempSelected.add(item);
                    // 滚动到右侧新添加的位置
                    Platform.runLater(() -> {
                        int idx = tempSelected.indexOf(item);
                        if (idx >= 0) rightListView.scrollTo(idx);
                    });
                }
            });
            leftListView.getSelectionModel().clearSelection();
        }

        private void moveSelectionLeft() {
            var items = new ArrayList<>(rightListView.getSelectionModel().getSelectedItems());
            tempSelected.removeAll(items);
            rightListView.getSelectionModel().clearSelection();
        }

        @FXML
        public void onMoveAllRight() {
            onSelectAll();
        }

        @FXML
        public void onMoveAllLeft() {
            onDeselectAll();
        }

        @FXML
        public void onSelectAll() {
            tempSelected.setAll(allItems);
        }

        @FXML
        public void onInvertSelection() {
            List<String> current = new ArrayList<>(tempSelected);
            tempSelected.clear();
            for (String item : allItems) {
                if (!current.contains(item)) tempSelected.add(item);
            }
        }

        @FXML
        public void onDeselectAll() {
            tempSelected.clear();
        }

        @FXML
        public void onRefresh() {
            if (onRefreshCallback != null) onRefreshCallback.run();
        }
    }

}
