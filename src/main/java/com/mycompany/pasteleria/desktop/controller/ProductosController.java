// src/main/java/com/mycompany/pasteleria/desktop/controller/ProductosController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.Producto;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser; // <-- IMPORT CLAVE

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ProductosController {

  @FXML private TextField txtBuscar;
  @FXML private TableView<Producto> tbl;
  @FXML private TableColumn<Producto, String> colId, colNombre, colCategoria, colPrecio, colStock, colEstado, colDesc;
  @FXML private ProgressIndicator loader;

  // === Helpers de requests (pégalos dentro de ProductosController) ===
private void execPost(String path, String json, String okMsg) {
  setLoading(true);
  Task<Void> t = new Task<>() {
    @Override protected Void call() throws Exception {
      var resp = api.postJson(path, json);
      int code = resp.statusCode();
      if (code >= 200 && code < 300) {
        Platform.runLater(() -> { info(okMsg); refrescar(); });
      } else {
        Platform.runLater(() -> { setLoading(false); alert("HTTP " + code + "\n" + resp.body()); });
      }
      return null;
    }
  };
  new Thread(t, "post-productos").start();
}

private void execPatch(String path, String json, String okMsg) {
  setLoading(true);
  Task<Void> t = new Task<>() {
    @Override protected Void call() throws Exception {
      var resp = api.patchJson(path, json);
      int code = resp.statusCode();
      if (code >= 200 && code < 300) {
        Platform.runLater(() -> { info(okMsg); refrescar(); });
      } else {
        Platform.runLater(() -> { setLoading(false); alert("HTTP " + code + "\n" + resp.body()); });
      }
      return null;
    }
  };
  new Thread(t, "patch-productos").start();
}

  // Botones (para deshabilitar durante cargas)
  @FXML private Button btnBuscar, btnRefrescar, btnNuevo, btnPrecio, btnStock, btnEstado, btnImagenes, btnExport;

  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  @FXML
  public void initialize() {
    colId.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
        String.valueOf(p.getValue().id_producto)));
    colNombre.setCellValueFactory(new PropertyValueFactory<>("nombre"));
    colCategoria.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
        p.getValue().getNombreCategoria()));
    colPrecio.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
        p.getValue().getPrecioBD().setScale(2).toPlainString()));
    colStock.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
        String.valueOf(p.getValue().stock == null ? 0 : p.getValue().stock)));
    colEstado.setCellValueFactory(new PropertyValueFactory<>("estado"));
    colDesc.setCellValueFactory(new PropertyValueFactory<>("descripcion"));

    txtBuscar.setOnAction(e -> buscar());
    refrescar();
  }

  @FXML public void buscar(){ load(txtBuscar.getText().trim()); }
  @FXML public void refrescar(){ load(""); }

  private void load(String filtro) {
    setLoading(true);
    // OJO: requiere FK productos.id_categoria -> categorias.id_categoria
    String select = enc("id_producto,nombre,descripcion,precio,stock,id_categoria,estado,categoria:categorias(nombre)");
    StringBuilder path = new StringBuilder("/productos?select=").append(select)
        .append("&order=nombre.asc&limit=500");

    if (!filtro.isBlank()) {
      String t = enc("*" + filtro + "*");
      path.append("&or=(nombre.ilike.").append(t).append(",categoria.nombre.ilike.").append(t).append(")");
    }

    Task<Void> task = new Task<>() {
      @Override protected Void call() throws Exception {
        var resp = api.getResp(path.toString());
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
          List<Producto> list = om.readValue(resp.body(), new TypeReference<List<Producto>>(){});
          Platform.runLater(() -> {
            tbl.setItems(FXCollections.observableArrayList(list));
            setLoading(false);
          });
        } else {
          Platform.runLater(() -> { setLoading(false); alert("HTTP "+code+"\n"+resp.body()); });
        }
        return null;
      }
    };
    new Thread(task,"load-productos").start();
  }

  @FXML public void nuevo() {
    // Formato rápido: nombre|id_categoria|precio|stock|estado|descripcion
    TextInputDialog d = new TextInputDialog();
    d.setTitle("Nuevo producto");
    d.setHeaderText(null);
    d.setContentText("nombre|id_categoria|precio|stock|estado|descripcion");
    d.getEditor().setPromptText("Torta Selva Negra|1|45.00|10|ACTIVO|Bizcocho cacao y crema");
    var res = d.showAndWait();
    if (res.isEmpty()) return;

    String[] p = res.get().split("\\|", -1);
    if (p.length < 6) { alert("Formato inválido"); return; }

    String json = "[{" +
        "\"nombre\":\""+esc(p[0])+"\"," +
        "\"id_categoria\":"+num(p[1]) + "," +
        "\"precio\":\""+esc(p[2])+"\"," +
        "\"stock\":"+num(p[3]) + "," +
        "\"estado\":\""+esc(p[4])+"\"," +
        "\"descripcion\":\""+esc(p[5])+"\"" +
        "}]";

    execPost("/productos", json, "Producto creado");
  }

  @FXML public void editarPrecio() {
    var sel = tbl.getSelectionModel().getSelectedItem();
    if (sel == null) { alert("Selecciona un producto"); return; }
    TextInputDialog d = new TextInputDialog(sel.getPrecioBD().toPlainString());
    d.setTitle("Editar precio");
    d.setHeaderText(sel.nombre);
    d.setContentText("Nuevo precio:");
    var res = d.showAndWait();
    if (res.isEmpty()) return;
    String precio = res.get().trim().replace(",", ".");
    String json = "{\"precio\":\""+esc(precio)+"\"}";
    execPatch("/productos?id_producto=eq." + sel.id_producto, json, "Precio actualizado");
  }

  @FXML public void editarStock() {
    var sel = tbl.getSelectionModel().getSelectedItem();
    if (sel == null) { alert("Selecciona un producto"); return; }
    TextInputDialog d = new TextInputDialog(String.valueOf(sel.stock == null ? 0 : sel.stock));
    d.setTitle("Editar stock");
    d.setHeaderText(sel.nombre);
    d.setContentText("Nuevo stock (entero):");
    var res = d.showAndWait();
    if (res.isEmpty()) return;
    String stock = res.get().trim();
    String json = "{\"stock\":"+num(stock)+"}";
    execPatch("/productos?id_producto=eq." + sel.id_producto, json, "Stock actualizado");
  }

  @FXML public void cambiarEstado() {
    var sel = tbl.getSelectionModel().getSelectedItem();
    if (sel == null) { alert("Selecciona un producto"); return; }
    ChoiceDialog<String> dialog = new ChoiceDialog<>(
        (sel.estado != null && sel.estado.equalsIgnoreCase("ACTIVO")) ? "INACTIVO" : "ACTIVO",
        "ACTIVO", "INACTIVO"
    );
    dialog.setTitle("Cambiar estado");
    dialog.setHeaderText(sel.nombre);
    dialog.setContentText("Estado:");
    var res = dialog.showAndWait();
    if (res.isEmpty()) return;
    String json = "{\"estado\":\""+esc(res.get())+"\"}";
    execPatch("/productos?id_producto=eq." + sel.id_producto, json, "Estado actualizado");
  }

  @FXML public void gestionarImagenes() {
    var sel = tbl.getSelectionModel().getSelectedItem();
    if (sel == null) { alert("Selecciona un producto"); return; }
    try {
      var url = getClass().getResource("/com/mycompany/pasteleria/desktop/view/ImagenesProducto.fxml");
      if (url == null) throw new IllegalStateException("Falta ImagenesProducto.fxml");

      Dialog<ButtonType> dlg = new Dialog<>();
      dlg.setTitle("Imágenes — " + sel.nombre);
      dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

      FXMLLoader loader = new FXMLLoader(url);
      Parent root = loader.load();

      // Intentar llamar setProducto(...) si el controller existe, sin depender en compilación
      Object ctrl = loader.getController();
      try {
        var m = ctrl.getClass().getMethod("setProducto", com.mycompany.pasteleria.desktop.model.Producto.class);
        m.invoke(ctrl, sel);
      } catch (NoSuchMethodException ignored) {
        // Si no existe el método, seguimos mostrando el FXML tal cual.
      }

      dlg.getDialogPane().setContent(root);
      dlg.showAndWait();

    } catch (Exception ex) {
      alert("No se pudo abrir gestor de imágenes.\n" + ex.getMessage());
    }
  }

  @FXML public void exportarCSV() {
    var items = tbl.getItems();
    if (items == null || items.isEmpty()) { alert("No hay datos para exportar."); return; }

    FileChooser fc = new FileChooser();
    fc.setTitle("Guardar productos (CSV)");
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
    fc.setInitialFileName("productos.csv");
    File f = fc.showSaveDialog(tbl.getScene().getWindow());
    if (f == null) return;

    try (BufferedWriter bw = new BufferedWriter(new FileWriter(f, false))) {
      bw.write("ID,Nombre,Categoría,Precio,Stock,Estado,Descripción"); bw.newLine();
      for (Producto p : items) {
        bw.write(csv(String.valueOf(p.id_producto))+","+
                 csv(p.nombre)+","+
                 csv(p.getNombreCategoria())+","+
                 csv(p.getPrecioBD().setScale(2).toPlainString())+","+
                 csv(String.valueOf(p.stock == null ? 0 : p.stock))+","+
                 csv(p.estado)+","+
                 csv(p.descripcion));
        bw.newLine();
      }
      info("Exportado: " + f.getAbsolutePath());
    } catch (Exception ex) { alert("Error al exportar: " + ex.getMessage()); }
  }

  /* ================= Helpers ================= */
  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }
  private static String esc(String x){ return x == null ? "" : x.replace("\"","\\\""); }
  private static String num(String x){ return (x == null || x.isBlank()) ? "0" : x.replaceAll("[^0-9.-]",""); }
  private static String csv(String s){
    String v = (s==null)?"":s; if (v.contains(",")||v.contains("\"")||v.contains("\n"))
      v = "\"" + v.replace("\"","\"\"") + "\""; return v;
  }

  private void setLoading(boolean v){
    if (loader!=null) loader.setVisible(v);
    if (tbl!=null) tbl.setDisable(v);
    if (btnBuscar!=null) btnBuscar.setDisable(v);
    if (btnRefrescar!=null) btnRefrescar.setDisable(v);
    if (btnNuevo!=null) btnNuevo.setDisable(v);
    if (btnPrecio!=null) btnPrecio.setDisable(v);
    if (btnStock!=null) btnStock.setDisable(v);
    if (btnEstado!=null) btnEstado.setDisable(v);
    if (btnImagenes!=null) btnImagenes.setDisable(v);
    if (btnExport!=null) btnExport.setDisable(v);
  }
  private void alert(String m){ new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait(); }
  private void info(String m){ new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait(); }
}
