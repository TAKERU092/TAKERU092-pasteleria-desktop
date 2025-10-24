// src/main/java/com/mycompany/pasteleria/desktop/controller/CategoriasController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.Categoria;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CategoriasController {

  @FXML private TextField txtBuscar;
  @FXML private TableView<Categoria> tbl;
  @FXML private TableColumn<Categoria, String> colId, colNombre, colDesc;
  @FXML private ProgressIndicator loader;

  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  @FXML
  public void initialize() {
    colId.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
        String.valueOf(p.getValue().id_categoria)));
    colNombre.setCellValueFactory(new PropertyValueFactory<>("nombre"));
    colDesc.setCellValueFactory(new PropertyValueFactory<>("descripcion"));

    txtBuscar.setOnAction(e -> buscar());
    refrescar();
  }

  @FXML public void buscar(){ load(txtBuscar.getText().trim()); }
  @FXML public void refrescar(){ load(""); }

  private void load(String filtro) {
    setLoading(true);
    String select = enc("id_categoria,nombre,descripcion");
    StringBuilder path = new StringBuilder("/categorias?select=").append(select)
        .append("&order=nombre.asc&limit=200");
    if (!filtro.isBlank()) {
      String t = enc("*" + filtro + "*");
      path.append("&nombre=ilike.").append(t);
    }

    Task<Void> task = new Task<>() {
      @Override protected Void call() throws Exception {
        var resp = api.getResp(path.toString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
          List<Categoria> list = om.readValue(resp.body(), new TypeReference<List<Categoria>>(){});
          javafx.application.Platform.runLater(() -> {
            tbl.setItems(FXCollections.observableArrayList(list));
            setLoading(false);
          });
        } else {
          javafx.application.Platform.runLater(() -> {
            setLoading(false);
            alert("HTTP "+resp.statusCode()+"\n"+resp.body());
          });
        }
        return null;
      }
    };
    new Thread(task,"load-categorias").start();
  }

  @FXML public void nueva() {
    TextInputDialog d = new TextInputDialog();
    d.setTitle("Nueva categoría");
    d.setHeaderText(null);
    d.setContentText("Formato: nombre|descripcion");
    d.getEditor().setPromptText("Tortas|Tortas clásicas");
    var res = d.showAndWait();
    if (res.isEmpty()) return;
    String[] p = res.get().split("\\|");
    if (p.length < 2) { alert("Formato inválido"); return; }

    String json = "[{\"nombre\":\""+esc(p[0])+"\",\"descripcion\":\""+esc(p[1])+"\"}]";
    execPost("/categorias", json, "Categoría creada");
  }

  @FXML public void renombrar() {
    var sel = tbl.getSelectionModel().getSelectedItem();
    if (sel == null) { alert("Selecciona una categoría"); return; }
    TextInputDialog d = new TextInputDialog(sel.nombre);
    d.setTitle("Renombrar categoría");
    d.setHeaderText(sel.nombre);
    d.setContentText("Nuevo nombre:");
    var res = d.showAndWait();
    if (res.isEmpty()) return;

    String json = "{\"nombre\":\""+esc(res.get().trim())+"\"}";
    execPatch("/categorias?id_categoria=eq." + sel.id_categoria, json, "Actualizado");
  }

  private void execPost(String path, String json, String okMsg) {
    setLoading(true);
    Task<Void> t = new Task<>() {
      @Override protected Void call() throws Exception {
        var resp = api.postJson(path, json);
        if (resp.statusCode()>=200 && resp.statusCode()<300) {
          javafx.application.Platform.runLater(() -> { info(okMsg); refrescar(); });
        } else {
          javafx.application.Platform.runLater(() -> { setLoading(false); alert("HTTP "+resp.statusCode()+"\n"+resp.body()); });
        }
        return null;
      }
    };
    new Thread(t,"post-categorias").start();
  }

  private void execPatch(String path, String json, String okMsg) {
    setLoading(true);
    Task<Void> t = new Task<>() {
      @Override protected Void call() throws Exception {
        var resp = api.patchJson(path, json);
        if (resp.statusCode()>=200 && resp.statusCode()<300) {
          javafx.application.Platform.runLater(() -> { info(okMsg); refrescar(); });
        } else {
          javafx.application.Platform.runLater(() -> { setLoading(false); alert("HTTP "+resp.statusCode()+"\n"+resp.body()); });
        }
        return null;
      }
    };
    new Thread(t,"patch-categorias").start();
  }

  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }
  private static String esc(String x){ return x.replace("\"","\\\""); }
  private void setLoading(boolean v){ if (loader!=null) loader.setVisible(v); if (tbl!=null) tbl.setDisable(v); }
  private void alert(String m){ new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait(); }
  private void info(String m){ new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait(); }
}
