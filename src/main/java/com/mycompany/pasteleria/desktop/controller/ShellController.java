package com.mycompany.pasteleria.desktop.controller;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public class ShellController {

  // Content
  @FXML private StackPane contentPane;

  // Topbar
  @FXML private Label lblTitle;
  @FXML private Label lblBreadcrumb;

  // Sidebar (IDs tal como están en MainView.fxml)
  @FXML private ToggleButton navDashboard;
  @FXML private ToggleButton navPagos;     // NUEVO: botón Pagos
  @FXML private ToggleButton navPedidos;
  @FXML private ToggleButton navRegistrar;
  @FXML private ToggleButton navCocina;
  @FXML private ToggleButton navEnvios;
  @FXML private ToggleButton navReportes;
  @FXML private ToggleButton navClientes;
  @FXML private ToggleButton navProductos;
  @FXML private ToggleButton navConfig;

  // Cache de vistas
  private final Map<String, Parent> viewCache = new HashMap<>();

  // Rutas FXML
  private static final String FXML_PAGOS      = "/com/mycompany/pasteleria/desktop/view/Pagos.fxml";      // NUEVO
  private static final String FXML_PEDIDOS    = "/com/mycompany/pasteleria/desktop/view/Pedidos.fxml";
  private static final String FXML_COCINA     = "/com/mycompany/pasteleria/desktop/view/Cocina.fxml"; // usa Cocina.fxml si corresponde
  private static final String FXML_PRODUCTOS  = "/com/mycompany/pasteleria/desktop/view/Productos.fxml";
  private static final String FXML_CATEGORIAS = "/com/mycompany/pasteleria/desktop/view/Categorias.fxml";

  // Por ahora “Dashboard” apunta a Categorías/Clientes (ajústalo cuando tengas Dashboard real)
  private static final String FXML_DASHBOARD  = FXML_CATEGORIAS;

  @FXML
  public void initialize() {
    // Carga inicial (igual que antes)
    showClientes();
  }

  // ====================== Handlers topbar ======================
  @FXML
  private void logout() {
    // Cierra directo; si prefieres confirmación, descomenta el bloque
    javafx.application.Platform.exit();

    /*
    Alert a = new Alert(Alert.AlertType.CONFIRMATION, "¿Cerrar sesión?", ButtonType.CANCEL, ButtonType.OK);
    a.setHeaderText(null);
    var res = a.showAndWait();
    if (res.isPresent() && res.get() == ButtonType.OK) {
      javafx.application.Platform.exit();
    }
    */
  }

  // ====================== Handlers sidebar ======================
  // Nota: en MainView.fxml mapeaste “Inicio (Dashboard)” a showClientes.
  @FXML
  public void showClientes() {
    select(navClientes, "Clientes", "Inicio / Clientes");
    loadIntoCenter("CLIENTES", FXML_DASHBOARD);
  }

  @FXML
  public void showPagos() {
    select(navPagos, "Pagos", "Inicio / Pagos");
    loadIntoCenter("PAGOS", FXML_PAGOS);
  }

  @FXML
  public void showPedidos() {
    select(navPedidos, "Pedidos", "Inicio / Pedidos");
    loadIntoCenter("PEDIDOS", FXML_PEDIDOS);
  }

  @FXML
  public void showCocina() {
    select(navCocina, "Cocina", "Inicio / Cocina");
    loadIntoCenter("COCINA", FXML_COCINA);
  }

  @FXML
  public void showProductos() {
    select(navProductos, "Productos", "Inicio / Productos");
    loadIntoCenter("PRODUCTOS", FXML_PRODUCTOS);
  }

  @FXML
  public void showCategorias() {
    select(navConfig, "Configuración", "Inicio / Configuración");
    loadIntoCenter("CATEGORIAS", FXML_CATEGORIAS);
  }

  // Placeholders (ya declarados en FXML; evitan errores si reasignas onAction)
  @FXML
  public void showDashboard() {
    select(navDashboard, "Inicio (Dashboard)", "Inicio / Dashboard");
    loadIntoCenter("DASHBOARD", FXML_DASHBOARD);
  }

  @FXML
  public void showRegistrar() {
    select(navRegistrar, "Registrar pedido", "Inicio / Registrar pedido");
    info("La vista 'Registrar pedido' todavía no está implementada.");
  }

  @FXML
  public void showEnvios() {
    select(navEnvios, "Envíos", "Inicio / Envíos");
    info("La vista 'Envíos' todavía no está implementada.");
  }

  @FXML
  public void showReportes() {
    select(navReportes, "Reportes", "Inicio / Reportes");
    info("La vista 'Reportes' todavía no está implementada.");
  }

  // ====================== Helpers ======================
  private void select(ToggleButton which, String title, String breadcrumb) {
    // Des-seleccionar todos y activar el actual
    if (navDashboard != null) navDashboard.setSelected(false);
    if (navPagos     != null) navPagos.setSelected(false);
    if (navPedidos   != null) navPedidos.setSelected(false);
    if (navRegistrar != null) navRegistrar.setSelected(false);
    if (navCocina       != null) navCocina.setSelected(false);
    if (navEnvios    != null) navEnvios.setSelected(false);
    if (navReportes  != null) navReportes.setSelected(false);
    if (navClientes  != null) navClientes.setSelected(false);
    if (navProductos != null) navProductos.setSelected(false);
    if (navConfig    != null) navConfig.setSelected(false);

    if (which != null) which.setSelected(true);
    if (lblTitle != null) lblTitle.setText("Pastelería — " + title);
    if (lblBreadcrumb != null) lblBreadcrumb.setText(breadcrumb);
  }

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
    // Log completo a consola
    ex.printStackTrace();

    // Construir cadena con causas anidadas
    StringBuilder sb = new StringBuilder();
    Throwable t = ex;
    while (t != null) {
      sb.append(t.getClass().getSimpleName())
        .append(": ")
        .append(t.getMessage() == null ? "(sin mensaje)" : t.getMessage())
        .append("\n");
      t = t.getCause();
    }

    new Alert(Alert.AlertType.ERROR,
        "No se pudo cargar la vista.\n" + resourcePath + "\n\nCausas:\n" + sb.toString()
    ).showAndWait();
  }
}


  private static String safeMsg(Throwable t) {
    String m = (t == null) ? null : t.getMessage();
    return (m == null || m.isBlank()) ? t.getClass().getName() : m;
  }

  private void info(String m) { new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait(); }
  private void error(String m) { new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait(); }
}
