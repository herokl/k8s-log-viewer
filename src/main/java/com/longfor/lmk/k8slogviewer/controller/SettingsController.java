package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.config.KubeConfigProfile;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SettingsController {
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    @FXML private ScrollPane settingsScrollPane;
    @FXML private VBox profileCardContainer;
    @FXML private TextField logRetentionDaysField;
    @FXML private TextField maxLogSizeField;
    @FXML private TextField logFlushIntervalField;
    @FXML private TextField searchRefreshIntervalField;
    @FXML private CheckBox treeAutoRefreshCheckBox;
    @FXML private TextField treeAutoRefreshIntervalField;

    private final ObservableList<KubeConfigProfile> profileList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        profileList.addAll(AppPreferences.getKubeConfigProfiles());
        profileList.addListener((javafx.collections.ListChangeListener<KubeConfigProfile>) c -> rebuildProfileCards());
        rebuildProfileCards();

        logRetentionDaysField.setText(String.valueOf(AppPreferences.getLogRetentionDays()));
        maxLogSizeField.setText(String.valueOf(AppPreferences.getMaxLogSizeMB()));
        logFlushIntervalField.setText(String.valueOf(AppPreferences.getLogFlushIntervalMs()));
        searchRefreshIntervalField.setText(String.valueOf(AppPreferences.getSearchRefreshIntervalMs()));
        treeAutoRefreshCheckBox.setSelected(AppPreferences.isTreeAutoRefresh());
        treeAutoRefreshIntervalField.setText(String.valueOf(AppPreferences.getTreeAutoRefreshIntervalSec()));
        treeAutoRefreshIntervalField.disableProperty().bind(treeAutoRefreshCheckBox.selectedProperty().not());
    }

    /** 重建配置卡片列表 */
    private void rebuildProfileCards() {
        profileCardContainer.getChildren().clear();
        String activeName = AppPreferences.getActiveProfileName();
        for (KubeConfigProfile p : profileList) {
            HBox card = createProfileCard(p, activeName);
            profileCardContainer.getChildren().add(card);
        }
    }

    /** 创建单个配置卡片（半圆角矩形，单行横排） */
    private HBox createProfileCard(KubeConfigProfile profile, String activeName) {
        boolean isActive = profile.getName().equals(activeName);

        Label nameLbl = new Label(profile.getName());
        nameLbl.setStyle("-fx-font-size: 13px; " + (isActive ? "-fx-font-weight: bold; -fx-text-fill: #326CE5;" : "-fx-text-fill: #2C3E50;"));
        nameLbl.setMinWidth(0);
        nameLbl.setMaxWidth(120);

        Label pathLbl = new Label(profile.getPath());
        pathLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #888;");
        pathLbl.setMaxWidth(Double.MAX_VALUE);
        pathLbl.setMinWidth(0);
        HBox.setHgrow(pathLbl, Priority.ALWAYS);
        if (!profile.getPath().isEmpty()) pathLbl.setTooltip(new Tooltip(profile.getPath()));

        Button deleteBtn = new Button("×");
        deleteBtn.getStyleClass().add("profile-card-delete-btn");
        deleteBtn.setOnAction(e -> removeProfileDirect(profile));

        HBox card = new HBox(12, nameLbl, pathLbl, deleteBtn);
        card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        card.getStyleClass().add("profile-card-row");

        // 双击编辑
        card.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                editProfile(profile);
                event.consume();
            }
        });

        return card;
    }

    /** 直接删除配置（从卡片按钮触发） */
    private void removeProfileDirect(KubeConfigProfile profile) {
        if (profileList.size() <= 1) {
            CommonUtils.showToast(settingsScrollPane, "✗", "至少保留一个配置", "#E74C3C");
            return;
        }
        String activeName = AppPreferences.getActiveProfileName();
        if (profile.getName().equals(activeName)) {
            CommonUtils.showToast(settingsScrollPane, "✗", "不能删除当前使用的配置", "#E74C3C");
            return;
        }
        profileList.remove(profile);
        AppPreferences.removeProfile(profile.getName());
    }

    @FXML
    public void onAddProfile() {
        AddProfileResult result = showAddProfileDialog(null, null, null);
        if (result == null) return;

        KubeConfigProfile profile = new KubeConfigProfile(result.name, result.path);
        profileList.removeIf(p -> p.getName().equals(profile.getName()));
        profileList.add(profile);
        AppPreferences.setKubeConfigProfiles(new ArrayList<>(profileList));
    }

    /** 双击编辑配置：弹出带预填值的对话框 */
    private void editProfile(KubeConfigProfile profile) {
        AddProfileResult result = showAddProfileDialog(profile.getName(), profile.getPath(), profile.getName());
        if (result == null) return;

        String newName = result.name;
        String newPath = result.path;

        // 先校验，再修改（避免修改后校验失败导致数据不一致）
        boolean isNameChanged = !profile.getName().equals(newName);
        if (isNameChanged) {
            long count = profileList.stream().filter(p -> p.getName().equals(newName)).count();
            if (count > 0) {
                CommonUtils.showToast(settingsScrollPane, "✗", "配置名称已存在", "#E74C3C");
                return;
            }
        }

        profile.setName(newName);
        profile.setPath(newPath);

        AppPreferences.setKubeConfigProfiles(new ArrayList<>(profileList));
        rebuildProfileCards();
    }

    /** 添加/编辑配置对话框：使用 Dialog 原生按钮栏（保存/取消在底部） */
    private AddProfileResult showAddProfileDialog(String defaultName, String defaultPath, String editingName) {
        try {
            URL url = getClass().getResource("/com/longfor/lmk/k8slogviewer/add_profile_dialog.fxml");
            FXMLLoader loader = new FXMLLoader(url);
            AddProfileController controller = new AddProfileController();
            loader.setController(controller);
            VBox content = loader.load();

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle(editingName != null ? "编辑 KubeConfig 配置" : "添加 KubeConfig 配置");
            dialog.getDialogPane().setContent(content);
            dialog.initOwner(AppConfig.getMainStage());
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

            // 使用原生按钮栏
            ButtonType saveBtn = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(saveBtn, cancelBtn);

            // 保存按钮触发前先校验
            dialog.getDialogPane().lookupButton(saveBtn).addEventFilter(
                    javafx.event.ActionEvent.ACTION, event -> {
                        controller.onConfirmAdd();
                        if (controller.getResult() == null) {
                            event.consume();  // 校验失败，阻止关闭
                        }
                    });

            controller.setDialog(dialog);

            // 预填值（编辑模式）
            if (defaultName != null || defaultPath != null) {
                controller.prefill(defaultName, defaultPath);
            }
            if (editingName != null) {
                controller.setEditingName(editingName);
            }

            Optional<ButtonType> result = dialog.showAndWait();
            if (!result.isPresent() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            return controller.getResult();
        } catch (IOException e) {
            log.error("加载添加配置对话框失败", e);
            return null;
        }
    }

    public void openSettingsDialog() throws IOException {
        URL url = getClass().getResource("/com/longfor/lmk/k8slogviewer/settings_dialog.fxml");
        FXMLLoader loader = new FXMLLoader(url);
        loader.setController(this);
        DialogPane dialogPane = loader.load();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("配置设置");
        dialog.setDialogPane(dialogPane);
        dialog.getDialogPane().getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());
        // 限制内容区最大高度，超出可滚动
        if (settingsScrollPane != null) {
            settingsScrollPane.setMaxHeight(360);
            settingsScrollPane.setMinHeight(200);
            settingsScrollPane.setPrefHeight(360);
            // 拦截滚轮事件，放大步进量
            settingsScrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
                if (event.getDeltaY() != 0) {
                    double delta = event.getDeltaY() * 4.0;
                    double current = settingsScrollPane.getVvalue();
                    settingsScrollPane.setVvalue(Math.max(0, Math.min(1, current - delta / settingsScrollPane.getContent().getBoundsInLocal().getHeight())));
                    event.consume();
                }
            });
        }
        dialog.initOwner(AppConfig.getMainStage());

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
            saveSettings();
            log.info("保存配置");
        } else {
            log.info("取消配置");
        }
    }

    private void saveSettings() {
        // 选中激活配置由点击决定，这里不再需要从 tableView 获取
        String prevKubeConfigPath = AppPreferences.getKubeConfigPath();

        // 保存日志保留天数
        String daysText = logRetentionDaysField.getText();
        if (daysText != null && !daysText.isBlank()) {
            try {
                int days = Integer.parseInt(daysText.trim());
                AppPreferences.setLogRetentionDays(days);
            } catch (NumberFormatException e) {
                log.warn("无效的日志保留天数: {}", daysText);
            }
        }

        // 保存最大日志容量
        String sizeText = maxLogSizeField.getText();
        if (sizeText != null && !sizeText.isBlank()) {
            try {
                int maxMB = Integer.parseInt(sizeText.trim());
                if (maxMB < 1) maxMB = 1;
                AppPreferences.setMaxLogSizeMB(maxMB);
            } catch (NumberFormatException e) {
                log.warn("无效的最大日志容量: {}", sizeText);
            }
        }

        // 保存日志刷新间隔
        String intervalText = logFlushIntervalField.getText();
        if (intervalText != null && !intervalText.isBlank()) {
            try {
                int ms = Integer.parseInt(intervalText.trim());
                AppPreferences.setLogFlushIntervalMs(ms);
            } catch (NumberFormatException e) {
                log.warn("无效的日志刷新间隔: {}", intervalText);
            }
        }

        // 保存搜索刷新间隔
        String searchIntervalText = searchRefreshIntervalField.getText();
        if (searchIntervalText != null && !searchIntervalText.isBlank()) {
            try {
                int ms = Integer.parseInt(searchIntervalText.trim());
                AppPreferences.setSearchRefreshIntervalMs(ms);
            } catch (NumberFormatException e) {
                log.warn("无效的搜索刷新间隔: {}", searchIntervalText);
            }
        }

        // 保存容器树自动刷新设置
        AppPreferences.setTreeAutoRefresh(treeAutoRefreshCheckBox.isSelected());
        String treeRefreshText = treeAutoRefreshIntervalField.getText();
        if (treeRefreshText != null && !treeRefreshText.isBlank()) {
            try {
                int sec = Integer.parseInt(treeRefreshText.trim());
                AppPreferences.setTreeAutoRefreshIntervalSec(sec);
            } catch (NumberFormatException e) {
                log.warn("无效的容器树刷新间隔: {}", treeRefreshText);
            }
        }

        // kubeconfig 路径变化后才重置 K8s 客户端（避免无关设置变更导致不必要的客户端重建）
        String curKubeConfigPath = AppPreferences.getKubeConfigPath();
        if (!Objects.equals(prevKubeConfigPath, curKubeConfigPath)) {
            K8sClientManager.reset();
        }
    }

    static class AddProfileResult {
        final String name;
        final String path;

        AddProfileResult(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    /** 添加配置对话框的控制器 */
    public static class AddProfileController {
        @FXML private TextField addProfileNameField;
        @FXML private TextField addProfilePathField;

        private Dialog<ButtonType> dialog;
        private AddProfileResult result;
        private String editingName;

        public void setDialog(Dialog<ButtonType> dialog) {
            this.dialog = dialog;
        }

        public void prefill(String name, String path) {
            addProfileNameField.setText(name != null ? name : "");
            addProfilePathField.setText(path != null ? path : "");
        }

        public void setEditingName(String name) {
            this.editingName = name;
        }

        public AddProfileResult getResult() {
            return result;
        }

        @FXML
        public void onBrowseKubeconfig() {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 kubeconfig 文件");

            String lastPath = getLastConfigDirectory();
            if (lastPath != null) {
                File dir = new File(lastPath);
                if (dir.exists() && dir.isDirectory()) {
                    chooser.setInitialDirectory(dir);
                }
            }

            File file = chooser.showOpenDialog(addProfilePathField.getScene().getWindow());
            if (file != null) {
                addProfilePathField.setText(file.getAbsolutePath());
                if (addProfileNameField.getText().isBlank()) {
                    File parentDir = file.getParentFile();
                    if (parentDir != null) {
                        addProfileNameField.setText(parentDir.getName());
                    } else {
                        addProfileNameField.setText("default");
                    }
                }
            }
        }

        private String getLastConfigDirectory() {
            List<KubeConfigProfile> profiles = AppPreferences.getKubeConfigProfiles();
            if (profiles.isEmpty()) return null;
            String lastPath = profiles.get(profiles.size() - 1).getPath();
            if (lastPath != null) {
                File parent = new File(lastPath).getParentFile();
                if (parent != null) return parent.getAbsolutePath();
            }
            return null;
        }

        /** 由 Dialog 原生"保存"按钮触发（通过 lookup 绑定或手动调用） */
        @FXML
        public void onConfirmAdd() {
            String name = addProfileNameField.getText() != null ? addProfileNameField.getText().trim() : "";
            String path = addProfilePathField.getText() != null ? addProfilePathField.getText().trim() : "";

            if (name.isEmpty()) {
                CommonUtils.showToast(addProfileNameField, "✗", "请输入配置名称", "#E74C3C");
                return;
            }
            if (path.isEmpty()) {
                CommonUtils.showToast(addProfilePathField, "✗", "请选择 kubeconfig 文件路径", "#E74C3C");
                return;
            }
            if (!new File(path).exists()) {
                CommonUtils.showToast(addProfilePathField, "✗", "文件不存在: " + path, "#E74C3C");
                return;
            }
            for (KubeConfigProfile p : AppPreferences.getKubeConfigProfiles()) {
                if (p.getName().equals(name) && !name.equals(editingName)) {
                    CommonUtils.showToast(addProfileNameField, "✗", "配置名称已存在，请换一个", "#E74C3C");
                    return;
                }
            }

            result = new AddProfileResult(name, path);
        }

        @FXML
        public void onCancel() {
            result = null;
        }
    }
}
