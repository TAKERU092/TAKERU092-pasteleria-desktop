// src/main/java/com/mycompany/pasteleria/desktop/controller/CategoriasController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.Categoria;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CategoriasController {

    // --- FXML ---
    @FXML private TextField txtBuscar;
    @FXML private TableView<Categoria> tbl;
    @FXML private TableColumn<Categoria, String> colId;
    @FXML private TableColumn<Categoria, String> colNombre;
    @FXML private TableColumn<Categoria, String> colDesc;
    @FXML private ProgressIndicator loader;
    @FXML private Button btnBuscar, btnRefrescar, btnNueva, btnEditar;

    // --- HTTP ---
    private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
    private final ObjectMapper om = new ObjectMapper();

    // guardamos el último filtro para recargar después de crear/editar
    private String ultimoFiltro = "";

    @FXML
    public void initialize() {
        // columnas
        colId.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(p.getValue().id_categoria)));
        colNombre.setCellValueFactory(new PropertyValueFactory<>("nombre"));
        colDesc.setCellValueFactory(new PropertyValueFactory<>("descripcion"));

        // que la tabla use el ancho disponible
        tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // que la descripción absorba el espacio libre
        colDesc.setMaxWidth(1f * Integer.MAX_VALUE);

        // buscar con ENTER
        txtBuscar.setOnAction(e -> buscar());

        // doble clic sobre fila -> editar
        tbl.setRowFactory(tv -> {
            TableRow<Categoria> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    editarCategoria(row.getItem());   // <- ahora SÍ existe
                }
            });
            return row;
        });

        // carga inicial
        refrescar();
    }

    /* ================== acciones top ================== */
    @FXML
    public void buscar() {
        String f = txtBuscar.getText() == null ? "" : txtBuscar.getText().trim();
        ultimoFiltro = f;
        load(f);
    }

    @FXML
    public void refrescar() {
        ultimoFiltro = "";
        txtBuscar.clear();
        load("");
    }

    @FXML
    public void nueva() {
        mostrarDialogoCategoria(null);
    }

    @FXML
    public void editar() {
        Categoria sel = tbl.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alert("Selecciona una categoría.");
            return;
        }
        editarCategoria(sel);
    }

    // pequeño wrapper para el doble clic y el botón Editar
    private void editarCategoria(Categoria c) {
        mostrarDialogoCategoria(c);
    }

    /* ================== carga desde supabase ================== */
    private void load(String filtro) {
        setLoading(true);

        String select = enc("id_categoria,nombre,descripcion");
        StringBuilder path = new StringBuilder("/categorias?select=")
                .append(select)
                .append("&order=nombre.asc&limit=200");

        // búsqueda por nombre O descripción
        if (filtro != null && !filtro.isBlank()) {
            String like = "*" + filtro.trim() + "*";
            String v = URLEncoder.encode(like, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("%2A", "*");   // mantener *

            path.append("&or=(")
                    .append("nombre.ilike.").append(v)
                    .append(",descripcion.ilike.").append(v)
                    .append(")");
        }

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                var resp = api.getResp(path.toString());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) {
                    List<Categoria> list = om.readValue(resp.body(), new TypeReference<List<Categoria>>() {});
                    Platform.runLater(() -> {
                        tbl.setItems(FXCollections.observableArrayList(list));
                        setLoading(false);
                    });
                } else {
                    Platform.runLater(() -> {
                        setLoading(false);
                        alert("HTTP " + code + "\n" + resp.body());
                    });
                }
                return null;
            }
        };
        new Thread(task, "load-categorias").start();
    }

    /* ================== crear / editar ================== */
    private void mostrarDialogoCategoria(Categoria base) {
        boolean editando = (base != null);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(editando ? "Editar categoría" : "Nueva categoría");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField txtNom = new TextField();
        txtNom.setPromptText("Nombre");
        if (editando && base.nombre != null) txtNom.setText(base.nombre);

        TextArea txtDesc = new TextArea();
        txtDesc.setPromptText("Descripción");
        txtDesc.setPrefRowCount(3);
        txtDesc.setWrapText(true);
        if (editando && base.descripcion != null) txtDesc.setText(base.descripcion);

        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new Insets(10));

        gp.add(new Label("Nombre:"), 0, 0);
        gp.add(txtNom, 1, 0);
        gp.add(new Label("Descripción:"), 0, 1);
        gp.add(txtDesc, 1, 1);

        GridPane.setHgrow(txtNom, Priority.ALWAYS);
        GridPane.setHgrow(txtDesc, Priority.ALWAYS);

        dialog.getDialogPane().setContent(gp);

        var res = dialog.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        String nombre = txtNom.getText() == null ? "" : txtNom.getText().trim();
        String desc   = txtDesc.getText() == null ? "" : txtDesc.getText().trim();

        if (nombre.isBlank()) {
            alert("El nombre es obligatorio.");
            return;
        }

        if (editando) {
            String json = "{\"nombre\":\"" + esc(nombre) + "\",\"descripcion\":\"" + esc(desc) + "\"}";
            execPatch("/categorias?id_categoria=eq." + base.id_categoria, json, "Categoría actualizada");
        } else {
            String json = "[{\"nombre\":\"" + esc(nombre) + "\",\"descripcion\":\"" + esc(desc) + "\"}]";
            execPost("/categorias", json, "Categoría creada");
        }
    }

    /* ================== helpers HTTP ================== */
    private void execPost(String path, String json, String okMsg) {
        setLoading(true);
        Task<Void> t = new Task<>() {
            @Override
            protected Void call() throws Exception {
                var resp = api.postJson(path, json);
                int code = resp.statusCode();
                Platform.runLater(() -> {
                    if (code >= 200 && code < 300) {
                        info(okMsg);
                        load(ultimoFiltro);
                    } else {
                        setLoading(false);
                        alert("HTTP " + code + "\n" + resp.body());
                    }
                });
                return null;
            }
        };
        new Thread(t, "post-categorias").start();
    }

    private void execPatch(String path, String json, String okMsg) {
        setLoading(true);
        Task<Void> t = new Task<>() {
            @Override
            protected Void call() throws Exception {
                var resp = api.patchJson(path, json);
                int code = resp.statusCode();
                Platform.runLater(() -> {
                    if (code >= 200 && code < 300) {
                        info(okMsg);
                        load(ultimoFiltro);
                    } else {
                        setLoading(false);
                        alert("HTTP " + code + "\n" + resp.body());
                    }
                });
                return null;
            }
        };
        new Thread(t, "patch-categorias").start();
    }

    /* ================== utilidades ================== */
    private static String enc(String x) {
        return URLEncoder.encode(x, StandardCharsets.UTF_8);
    }

    private static String esc(String x) {
        return x == null ? "" : x.replace("\"", "\\\"");
    }

    private void setLoading(boolean v) {
        if (loader != null) loader.setVisible(v);
        if (tbl != null) tbl.setDisable(v);
        if (txtBuscar != null) txtBuscar.setDisable(v);
        if (btnBuscar != null) btnBuscar.setDisable(v);
        if (btnRefrescar != null) btnRefrescar.setDisable(v);
        if (btnNueva != null) btnNueva.setDisable(v);
        if (btnEditar != null) btnEditar.setDisable(v);
    }

    private void alert(String m) {
        new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait();
    }

    private void info(String m) {
        new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait();
    }
}
