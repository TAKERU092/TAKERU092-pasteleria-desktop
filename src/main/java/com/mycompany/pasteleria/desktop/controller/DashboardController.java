// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/DashboardController.java
package com.mycompany.pasteleria.desktop.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.net.ApiClient;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;

/**
 * Dashboard simple: lee TODOS los pedidos (solo campo estado)
 * y los cuenta en memoria. No usa GROUP BY para evitar el 400.
 */
public class DashboardController {

    // ====== UI (10 estados fijos) ======
    @FXML private Label lblEnRevision;
    @FXML private Label lblAprobado;
    @FXML private Label lblPagoNoValido;
    @FXML private Label lblEnCocina;
    @FXML private Label lblEnPreparacion;
    @FXML private Label lblServido;
    @FXML private Label lblAsignado;
    @FXML private Label lblEnviado;
    @FXML private Label lblEntregado;
    @FXML private Label lblNoEncontrado;

    @FXML private Button btnRefrescar;
    @FXML private ProgressIndicator loader;
    @FXML private StackPane overlayPane;

    // ====== HTTP ======
    private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
    private final ObjectMapper om = new ObjectMapper();

    // AJUSTA ESTO si tu tabla se llama distinto
    private static final String PEDIDOS_ENDPOINT = "/pedidos";
    // para no traer infinito
    private static final int MAX_ROWS = 2000;

    @FXML
    public void initialize() {
        if (btnRefrescar != null) {
            btnRefrescar.setOnAction(e -> cargar());
        }
        cargar();
    }

    private void cargar() {
        setLoading(true);

        // SOLO traemos la columna estado (y ordenamos para que sea más prolijo)
        // IMPORTANTE: NADA de group=estado aquí.
        String select = enc("estado");
        String path = PEDIDOS_ENDPOINT
                + "?select=" + select
                + "&order=estado.asc"
                + "&limit=" + MAX_ROWS;

        Task<List<Map<String, Object>>> t = new Task<>() {
            @Override
            protected List<Map<String, Object>> call() throws Exception {
                var resp = api.getResp(path);
                int code = resp.statusCode();
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code + " al leer pedidos.\n" + resp.body());
                }
                return om.readValue(resp.body(), new TypeReference<>() {});
            }
        };

        t.setOnSucceeded(ev -> {
            List<Map<String, Object>> data = t.getValue();
            contarYMostrar(data);
            setLoading(false);
        });

        t.setOnFailed(ev -> {
            setLoading(false);
            Throwable ex = t.getException();
            String msg = (ex == null || ex.getMessage() == null) ? "Error desconocido" : ex.getMessage();
            showError("No se pudo cargar estados de pedidos.\n" + msg);
        });

        new Thread(t, "dash-estados-pedidos").start();
    }

    private void contarYMostrar(List<Map<String, Object>> filas) {
        // iniciamos todos los estados que quieres ver
        Map<String, Integer> counts = new HashMap<>();
        counts.put("EN_REVISION",   0);
        counts.put("APROBADO",      0);
        counts.put("PAGO_NO_VALIDO",0);
        counts.put("EN_COCINA",     0);
        counts.put("EN_PREPARACION",0);
        counts.put("SERVIDO",       0);
        counts.put("ASIGNADO",      0);
        counts.put("ENVIADO",       0);
        counts.put("ENTREGADO",     0);
        counts.put("NO_ENCONTRADO", 0);

        for (Map<String, Object> row : filas) {
            // puede venir {"estado":"EN_COCINA"} o {"estado":null}
            Object estObj = row.get("estado");
            String raw = (estObj == null) ? "SIN_ESTADO" : estObj.toString();
            String norm = normalizar(raw); // EN_COCINA, EN_REVISION, etc.
            if (counts.containsKey(norm)) {
                counts.put(norm, counts.get(norm) + 1);
            }
        }

        // pintar
        lblEnRevision.setText(     String.valueOf(counts.get("EN_REVISION")) );
        lblAprobado.setText(       String.valueOf(counts.get("APROBADO")) );
        lblPagoNoValido.setText(   String.valueOf(counts.get("PAGO_NO_VALIDO")) );
        lblEnCocina.setText(       String.valueOf(counts.get("EN_COCINA")) );
        lblEnPreparacion.setText(  String.valueOf(counts.get("EN_PREPARACION")) );
        lblServido.setText(        String.valueOf(counts.get("SERVIDO")) );
        lblAsignado.setText(       String.valueOf(counts.get("ASIGNADO")) );
        lblEnviado.setText(        String.valueOf(counts.get("ENVIADO")) );
        lblEntregado.setText(      String.valueOf(counts.get("ENTREGADO")) );
        lblNoEncontrado.setText(   String.valueOf(counts.get("NO_ENCONTRADO")) );
    }

    // ====== helpers ======
    private void setLoading(boolean v) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setLoading(v));
            return;
        }
        if (loader != null) loader.setVisible(v);
        if (btnRefrescar != null) btnRefrescar.setDisable(v);
    }

    private static String enc(String x) {
        return URLEncoder.encode(x, StandardCharsets.UTF_8);
    }

    /** Normaliza a MAYÚSCULAS_SIN_TILDES_Y_CON_GUIONES_BAJOS */
    private static String normalizar(String s) {
        if (s == null) return "SIN_ESTADO";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");              // sin tildes
        n = n.toUpperCase(Locale.ROOT);                 // mayúsculas
        n = n.replace(' ', '_').replace('-', '_');      // espacios y guiones
        n = n.replaceAll("_+", "_");                    // evitar __
        return n.trim();
    }

    private void showError(String msg) {
        Platform.runLater(() ->
                new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait()
        );
    }
}
