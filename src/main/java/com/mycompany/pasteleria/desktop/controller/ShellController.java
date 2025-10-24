// src/main/java/com/mycompany/pasteleria/desktop/controller/ShellController.java
package com.mycompany.pasteleria.desktop.controller;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public class ShellController {

  @FXML private StackPane contentPane;
  @FXML private ToggleButton btnClientes, btnPedidos, btnCocina, btnProductos, btnCategorias;
  @FXML private Label lblTitle;

  // Cache de vistas
  private final Map<String, Parent> viewCache = new HashMap<>();
  private boolean acceleratorsInstalled = false;

  // Rutas FXML
  private static final String FXML_CLIENTES   = "/com/mycompany/pasteleria/desktop/view/Dashboard.fxml";
  private static final String FXML_PEDIDOS    = "/com/mycompany/pasteleria/desktop/view/Pedidos.fxml";
  private static final String FXML_COCINA     = "/com/mycompany/pasteleria/desktop/view/Cocina.fxml";
  private static final String FXML_PRODUCTOS  = "/com/mycompany/pasteleria/desktop/view/Productos.fxml";
  private static final String FXML_CATEGORIAS = "/com/mycompany/pasteleria/desktop/view/Categorias.fxml";

  @FXML
  public void initialize() {
    // Toggle exclusivo
    ToggleGroup group = new ToggleGroup();
    if (btnClientes   != null) btnClientes.setToggleGroup(group);
    if (btnPedidos    != null) btnPedidos.setToggleGroup(group);
    if (btnCocina     != null) btnCocina.setToggleGroup(group);
    if (btnProductos  != null) btnProductos.setToggleGroup(group);
    if (btnCategorias != null) btnCategorias.setToggleGroup(group);

    // Carga inicial
    showClientes();

    // Atajos (una sola vez)
    contentPane.sceneProperty().addListener((obs, oldScene, scene) -> {
      if (scene == null || acceleratorsInstalled) return;
      acceleratorsInstalled = true;

      scene.getAccelerators().put(
          new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
          this::showClientes
      );
      scene.getAccelerators().put(
          new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN),
          this::showPedidos
      );
      scene.getAccelerators().put(
          new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN),
          this::showCocina
      );
      scene.getAccelerators().put(
          new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.SHORTCUT_DOWN),
          this::showProductos
      );
      scene.getAccelerators().put(
          new KeyCodeCombination(KeyCode.DIGIT5, KeyCombination.SHORTCUT_DOWN),
          this::showCategorias
      );
    });
  }

  // -------- Navegación
  @FXML
  public void showClientes() {
    setSelected(btnClientes);
    setTitle("Clientes");
    loadIntoCenter("CLIENTES", FXML_CLIENTES);
  }

  @FXML
  public void showPedidos() {
    setSelected(btnPedidos);
    setTitle("Pedidos");
    loadIntoCenter("PEDIDOS", FXML_PEDIDOS);
  }

  @FXML
  public void showCocina() {
    setSelected(btnCocina);
    setTitle("Cocina");
    loadIntoCenter("COCINA", FXML_COCINA);
  }

  @FXML
  public void showProductos() {
    setSelected(btnProductos);
    setTitle("Productos");
    loadIntoCenter("PRODUCTOS", FXML_PRODUCTOS);
  }

  @FXML
  public void showCategorias() {
    setSelected(btnCategorias);
    setTitle("Categorías");
    loadIntoCenter("CATEGORIAS", FXML_CATEGORIAS);
  }

  private void setSelected(ToggleButton which) {
    if (btnClientes   != null) btnClientes.setSelected(false);
    if (btnPedidos    != null) btnPedidos.setSelected(false);
    if (btnCocina     != null) btnCocina.setSelected(false);
    if (btnProductos  != null) btnProductos.setSelected(false);
    if (btnCategorias != null) btnCategorias.setSelected(false);
    if (which != null) which.setSelected(true);
  }

  private void setTitle(String page) {
    if (lblTitle != null) lblTitle.setText("Pastelería — " + page);
  }

  // Carga (con cache) y transición
  private void loadIntoCenter(String key, String resourcePath) {
    try {
      Parent view = viewCache.get(key);
      if (view == null) {
        URL url = getClass().getResource(resourcePath);
        if (url == null) throw new IllegalStateException("No se encontró FXML: " + resourcePath);
        FXMLLoader loader = new FXMLLoader(url);
        view = loader.load();
        viewCache.put(key, view);
      }

      contentPane.getChildren().setAll(view);

      FadeTransition ft = new FadeTransition(Duration.millis(180), view);
      ft.setFromValue(0.0);
      ft.setToValue(1.0);
      ft.play();

    } catch (Exception ex) {
      new Alert(Alert.AlertType.ERROR,
          "No se pudo cargar la vista.\n" + resourcePath + "\n" + safeMsg(ex)
      ).showAndWait();
    }
  }

  private static String safeMsg(Throwable t) {
    String m = (t == null) ? null : t.getMessage();
    return (m == null || m.isBlank()) ? t.getClass().getName() : m;
  }
}


