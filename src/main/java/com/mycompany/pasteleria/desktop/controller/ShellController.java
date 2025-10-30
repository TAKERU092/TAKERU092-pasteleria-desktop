// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/ShellController.java
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

    // ========== CENTER ==========
    @FXML private StackPane contentPane;

    // ========== TOPBAR ==========
    @FXML private Label lblTitle;
    @FXML private Label lblBreadcrumb;
    @FXML private Label lblUser;   // están en el FXML
    @FXML private Label lblClock;  // están en el FXML

    // ========== SIDEBAR ==========
    @FXML private ToggleButton navDashboard;
    @FXML private ToggleButton navPagos;
    @FXML private ToggleButton navPedidos;
    @FXML private ToggleButton navRegistrar;
    @FXML private ToggleButton navCocina;
    @FXML private ToggleButton navEnvios;
    @FXML private ToggleButton navReportes;
    @FXML private ToggleButton navClientes;
    @FXML private ToggleButton navProductos;
    @FXML private ToggleButton navConfig;

    // ========== CACHE ==========
    private final Map<String, Parent> viewCache = new HashMap<>();

    // ========== RUTAS FXML ==========
    private static final String FXML_DASHBOARD  = "/com/mycompany/pasteleria/desktop/view/Dashboard.fxml";
    private static final String FXML_PAGOS      = "/com/mycompany/pasteleria/desktop/view/Pagos.fxml";
    private static final String FXML_PEDIDOS    = "/com/mycompany/pasteleria/desktop/view/Pedidos.fxml";
    private static final String FXML_COCINA     = "/com/mycompany/pasteleria/desktop/view/Cocina.fxml";
    private static final String FXML_PRODUCTOS  = "/com/mycompany/pasteleria/desktop/view/Productos.fxml";
    private static final String FXML_CATEGORIAS = "/com/mycompany/pasteleria/desktop/view/Categorias.fxml";

    // Vistas que todavía no existen: reciclamos
    private static final String FXML_REGISTRAR  = FXML_PEDIDOS;
    private static final String FXML_ENVIOS     = FXML_PEDIDOS;
    private static final String FXML_REPORTES   = FXML_DASHBOARD;

    // ========== INIT ==========
    @FXML
    public void initialize() {
        // al iniciar, muestro el dashboard real
        showDashboard();
    }

    // ========== TOPBAR ==========
    @FXML
    private void logout() {
        // directo
        javafx.application.Platform.exit();

        // si quieres confirmación, usa esto:
        /*
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "¿Cerrar sesión?",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText(null);
        var res = a.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            javafx.application.Platform.exit();
        }
        */
    }

    // ========== HANDLERS MENU ==========
    @FXML
    public void showDashboard() {
        go(
            "DASHBOARD",
            navDashboard,
            FXML_DASHBOARD,
            "Dashboard",
            "Inicio / Dashboard"
        );
    }

    @FXML
    public void showPagos() {
        go(
            "PAGOS",
            navPagos,
            FXML_PAGOS,
            "Pagos",
            "Inicio / Pagos"
        );
    }

    @FXML
    public void showPedidos() {
        go(
            "PEDIDOS",
            navPedidos,
            FXML_PEDIDOS,
            "Pedidos",
            "Inicio / Pedidos"
        );
    }

    @FXML
    public void showRegistrar() {
        // por ahora reusa la vista de pedidos
        go(
            "REGISTRAR",
            navRegistrar,
            FXML_REGISTRAR,
            "Registrar pedido",
            "Inicio / Registrar pedido"
        );
    }

    @FXML
    public void showCocina() {
        go(
            "COCINA",
            navCocina,
            FXML_COCINA,
            "Cocina",
            "Inicio / Cocina"
        );
    }

    @FXML
    public void showEnvios() {
        // por ahora reusa pedidos
        go(
            "ENVIOS",
            navEnvios,
            FXML_ENVIOS,
            "Envíos",
            "Inicio / Envíos"
        );
    }

    @FXML
    public void showReportes() {
        // por ahora reusa dashboard
        go(
            "REPORTES",
            navReportes,
            FXML_REPORTES,
            "Reportes",
            "Inicio / Reportes"
        );
    }

    @FXML
    public void showClientes() {
        // usas Categorias.fxml como “Clientes”
        go(
            "CLIENTES",               // <-- key distinta
            navClientes,
            FXML_CATEGORIAS,
            "Clientes",
            "Inicio / Clientes"
        );
    }

    @FXML
    public void showProductos() {
        go(
            "PRODUCTOS",
            navProductos,
            FXML_PRODUCTOS,
            "Productos",
            "Inicio / Productos"
        );
    }

    @FXML
    public void showCategorias() {
        go(
            "CATEGORIAS",
            navConfig,
            FXML_CATEGORIAS,
            "Configuración",
            "Inicio / Configuración / Categorías"
        );
    }

    // ========== NÚCLEO DE NAVEGACIÓN ==========
    private void go(String key,
                    ToggleButton btn,
                    String resourcePath,
                    String title,
                    String breadcrumb) {

        // 1) marcar botón actual
        selectOnly(btn);

        // 2) cargar / obtener de cache
        Parent view = loadView(key, resourcePath);
        if (view == null) {
            error("No se pudo cargar la vista: " + resourcePath);
            return;
        }

        // 3) ponerlo en el centro
        contentPane.getChildren().setAll(view);

        // 4) animación
        FadeTransition ft = new FadeTransition(Duration.millis(160), view);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        ft.play();

        // 5) actualizar títulos
        if (lblTitle != null) {
            lblTitle.setText("Pastelería — " + title);
        }
        if (lblBreadcrumb != null) {
            lblBreadcrumb.setText(breadcrumb);
        }
    }

    private Parent loadView(String key, String resourcePath) {
        try {
            if (viewCache.containsKey(key)) {
                return viewCache.get(key);
            }
            URL url = getClass().getResource(resourcePath);
            if (url == null) {
                throw new IllegalStateException("No se encontró FXML: " + resourcePath);
            }
            FXMLLoader loader = new FXMLLoader(url);
            Parent view = loader.load();
            viewCache.put(key, view);
            return view;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private void selectOnly(ToggleButton active) {
        // desactiva todos
        if (navDashboard != null) navDashboard.setSelected(false);
        if (navPagos     != null) navPagos.setSelected(false);
        if (navPedidos   != null) navPedidos.setSelected(false);
        if (navRegistrar != null) navRegistrar.setSelected(false);
        if (navCocina    != null) navCocina.setSelected(false);
        if (navEnvios    != null) navEnvios.setSelected(false);
        if (navReportes  != null) navReportes.setSelected(false);
        if (navClientes  != null) navClientes.setSelected(false);
        if (navProductos != null) navProductos.setSelected(false);
        if (navConfig    != null) navConfig.setSelected(false);

        // activa el actual
        if (active != null) active.setSelected(true);
    }

    // ========== ALERTAS ==========
    private void error(String m) {
        new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait();
    }

    @SuppressWarnings("unused")
    private void info(String m) {
        new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait();
    }
}
