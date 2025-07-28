package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.KubernetesLogViewer;
import com.longfor.lmk.k8slogviewer.util.AlertUtil;
import com.longfor.lmk.k8slogviewer.util.InstanceUtils;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * 主界面 FXML 控制器，处理 UI 事件并与 LogControllerUtil 协作
 */
public class MainController {
    @FXML private TreeView<String> navigationTree;
    @FXML private TextArea logArea;
    @FXML private Button pauseResumeButton;
    @FXML private TextField searchField;
    @FXML private CheckBox caseSensitiveCheck;
    @FXML private Button searchButton;
    @FXML private Button settingsButton;
    @FXML private ComboBox<String> searchModeCombo;
    @FXML private TextField treeSearchField;
    @FXML private Button treeSearchButton;

    /**
     * 初始化控制器，设置 Kubernetes API 和事件监听
     * @param api Kubernetes API 客户端
     */
    public void setApi(CoreV1Api api) {
        InstanceUtils.getLogControllerInstance().populateNamespaces(navigationTree);
        navigationTree.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldVal, newVal) -> InstanceUtils.getLogControllerInstance().handleTreeSelection(newVal));
        searchModeCombo.getSelectionModel().selectFirst();
        searchButton.setOnAction(e -> searchLogs());
        treeSearchButton.setOnAction(e -> searchTree());

        // 为搜索模式添加图标
        searchModeCombo.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    try {
                        String iconPath = switch (item) {
                            case "模糊匹配" -> "/com/longfor/lmk/k8slogviewer/icons/fuzzy.png";
                            case "精确匹配" -> "/com/longfor/lmk/k8slogviewer/icons/exact.png";
                            case "正则表达式" -> "/com/longfor/lmk/k8slogviewer/icons/regex.png";
                            default -> null;
                        };
                        if (iconPath != null) {
                            ImageView icon = new ImageView(new Image(Objects.requireNonNull(getClass().getResourceAsStream(iconPath))));
                            icon.setFitWidth(16);
                            icon.setFitHeight(16);
                            setGraphic(icon);
                        } else {
                            // 备用：使用 FontAwesome 图标
                            setIcon(item);
                        }
                    } catch (Exception e) {
                        // 若 PNG 加载失败，使用文本图标
                        setIcon(item);
                    }
                }
            }

            private void setIcon(String item) {
                Text icon = new Text(switch (item) {
                    case "模糊匹配" -> "\uF002"; // FontAwesome 放大镜
                    case "精确匹配" -> "\uF52C"; // FontAwesome 等号
                    case "正则表达式" -> "\uF0C9"; // FontAwesome 波浪线
                    default -> "";
                });
                icon.setStyle("-fx-font-family: 'FontAwesome'; -fx-font-size: 14px;");
                setGraphic(icon);
            }
        });
        searchModeCombo.setButtonCell(searchModeCombo.getCellFactory().call(null));
        // 为设置按钮设置图标
        try {
            ImageView settingsIcon = new ImageView(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))));
            settingsIcon.setFitWidth(16);
            settingsIcon.setFitHeight(16);
            settingsButton.setGraphic(settingsIcon);
        } catch (Exception e) {
            // 备用：使用 FontAwesome 齿轮图标
            Text settingsIcon = new Text("\uF013"); // FontAwesome 齿轮
            settingsIcon.setStyle("-fx-font-family: 'FontAwesome'; -fx-font-size: 14px;");
            settingsButton.setGraphic(settingsIcon);
        }
    }

    /**
     * 更新日志显示区域
     * @param content 日志内容
     */
    public void updateLogArea(String content) {
        logArea.appendText(content + "\n");
        logArea.setScrollTop(Double.MAX_VALUE);
    }

    /**
     * 处理暂停/恢复按钮点击事件
     */
    @FXML
    private void togglePauseResume() {
        InstanceUtils.getLogControllerInstance().togglePauseResume(pauseResumeButton);
    }

    /**
     * 打开设置页面
     */
    @FXML
    private void openSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(KubernetesLogViewer.class.getResource("/com/longfor/lmk/k8slogviewer/settings.fxml"));
            if (loader.getLocation() == null) {
                throw new RuntimeException("无法找到 settings.fxml 文件，请检查资源路径");
            }
            Scene scene = new Scene(loader.load(), 600, 400);
            scene.getStylesheets().add(Objects.requireNonNull(KubernetesLogViewer.class.getResource("/styles.css")).toExternalForm());

            Stage settingsStage = new Stage();
            settingsStage.setTitle("设置");
            settingsStage.initModality(Modality.APPLICATION_MODAL);
            settingsStage.setScene(scene);

            SettingsController controller = loader.getController();
            controller.setMainApp((KubernetesLogViewer) getClass().getClassLoader().loadClass("com.longfor.lmk.k8slogviewer.KubernetesLogViewer").getDeclaredConstructor().newInstance());
            controller.setMainController(this);

            settingsStage.showAndWait();
        } catch (Exception e) {
            AlertUtil.showError("错误", "无法打开设置页面: " + e.getMessage());
        }
    }

    /**
     * 刷新导航树
     */
    public void refreshNavigationTree() {
        InstanceUtils.getLogControllerInstance().populateNamespaces(navigationTree);
    }

    /**
     * 处理导航树搜索
     */
    @FXML
    private void searchTree() {
        String searchTerm = treeSearchField.getText().trim();
        if (searchTerm.isEmpty()) {
            AlertUtil.showError("提示", "请输入搜索关键词");
            return;
        }
        boolean found = InstanceUtils.getLogControllerInstance().searchTreeNodes(navigationTree, searchTerm);
        if (!found) {
            AlertUtil.showError("提示", "未找到匹配的命名空间、Pod 或容器");
        }
    }

    /**
     * 处理日志搜索
     */
    @FXML
    private void searchLogs() {
        InstanceUtils.getLogControllerInstance().performSearch(
                searchField.getText(), searchModeCombo.getValue(), caseSensitiveCheck.isSelected(), logArea);
    }
}