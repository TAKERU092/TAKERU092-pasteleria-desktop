// src/main/java/com/mycompany/pasteleria/desktop/controller/ImagenesProductoController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.ImagenProducto;
import com.mycompany.pasteleria.desktop.model.Producto;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ImagenesProductoController {

  @FXML private Label lblTitulo;
  @FXML private TableView<ImagenProducto> tbl;
  @FXML private TableColumn<ImagenProducto, String> colOrden, colUrl;
  @FXML private TextField txtUrl;
  @FXML private ProgressIndicator loader;

  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  private Producto producto;
  private List<ImagenProducto> cache = new ArrayList<>();

  public void setProducto(Producto p) {
    this.producto = p;
    lblTitulo.setText("Imágenes — " + p.nombre + " (ID " + p.id_producto + ")");
    cargar();
  }

  @FXML
  public void initialize() {
    colOrden.setCellValueFactory(ip -> new javafx.beans.property.SimpleStringProperty(
        String.valueOf(ip.getValue().orden == null ? 0 : ip.getValue().orden)));
    colUrl.setCellValueFactory(new PropertyValueFactory<>("url"));
  }

  private void cargar() {
    setLoading(true);
    String select = enc("id_imagen,id_producto,url,orden");
    String path = "/imagenes_producto?select=" + select + "&id_producto=eq." + producto.id_producto + "&order=orden.asc";
    Task<Void> t = new Task<>() {
      @Override protected Void call() throws Exception {
        var resp = api.getResp(path);
        int code = resp.statusCode();
        if (code>=200 && code<300) {
          List<ImagenProducto> list = om.readValue(resp.body(), new TypeReference<List<ImagenProducto>>(){});
          Platform.runLater(() -> {
            cache = new ArrayList<>(list);
            tbl.setItems(FXCollections.observableArrayList(cache));
            setLoading(false);
          });
        } else {
          Platform.runLater(() -> { setLoading(false); alert("HTTP "+code+"\n"+resp.body()); });
        }
        return null;
      }
    };
    new Thread(t,"load-imagenes").start();
  }

  @FXML public void addUrl() {
    String url = txtUrl.getText();
    if (url == null || url.isBlank()) { alert("Ingresa una URL"); return; }
    int nextOrden = cache.stream().map(i -> i.orden==null?0:i.orden).max(Comparator.naturalOrder()).orElse(0) + 1;
    ImagenProducto np = new ImagenProducto();
    np.id_imagen = null; // nueva
    np.id_producto = producto.id_producto;
    np.url = url.trim();
    np.orden = nextOrden;
    cache.add(np);
    tbl.getItems().setAll(cache);
    txtUrl.clear();
  }

  @FXML public void removeSelected() {
    ImagenProducto sel = tbl.getSelectionModel().getSelectedItem();
    if (sel == null) return;
    cache.remove(sel);
    tbl.getItems().setAll(cache);
  }

  @FXML public void moveUp() {
    int idx = tbl.getSelectionModel().getSelectedIndex();
    if (idx <= 0) return;
    var list = tbl.getItems();
    var a = list.get(idx-1);
    var b = list.get(idx);
    list.set(idx-1, b);
    list.set(idx, a);
    resecuenciar(list);
    tbl.getSelectionModel().select(idx-1);
  }

  @FXML public void moveDown() {
    int idx = tbl.getSelectionModel().getSelectedIndex();
    var list = tbl.getItems();
    if (idx < 0 || idx >= list.size()-1) return;
    var a = list.get(idx);
    var b = list.get(idx+1);
    list.set(idx, b);
    list.set(idx+1, a);
    resecuenciar(list);
    tbl.getSelectionModel().select(idx+1);
  }

  private void resecuenciar(List<ImagenProducto> list){
    int o = 1;
    for (ImagenProducto i : list) i.orden = o++;
    cache = new ArrayList<>(list);
  }

  @FXML public void guardar() {
    // Estrategia simple:
    // - Eliminar todas las imágenes de ese producto
    // - Insertar el array actual con orden actualizado
    setLoading(true);
    Task<Void> t = new Task<>() {
      @Override protected Void call() throws Exception {
        // 1) DELETE
        var del = api.delete("/imagenes_producto?id_producto=eq." + producto.id_producto);
        if (del.statusCode() < 200 || del.statusCode() >= 300) {
          throw new RuntimeException("DELETE HTTP "+del.statusCode()+"\n"+del.body());
        }

        // 2) POST array
        var arr = new ArrayList<java.util.Map<String,Object>>();
        for (ImagenProducto i : cache) {
          var m = new java.util.LinkedHashMap<String,Object>();
          m.put("id_producto", producto.id_producto);
          m.put("url", i.url);
          m.put("orden", i.orden);
          arr.add(m);
        }
        String json = new ObjectMapper().writeValueAsString(arr);
        var post = api.postJson("/imagenes_producto", json);
        if (post.statusCode() < 200 || post.statusCode() >= 300) {
          throw new RuntimeException("POST HTTP "+post.statusCode()+"\n"+post.body());
        }
        return null;
      }
    };
    t.setOnSucceeded(e -> { info("Imágenes guardadas"); setLoading(false); cargar(); });
    t.setOnFailed(e -> { setLoading(false); alert("No se pudo guardar.\n"+(t.getException()!=null?t.getException().getMessage():"")); });
    new Thread(t,"save-imagenes").start();
  }

  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }
  private void setLoading(boolean v){ if (loader!=null) loader.setVisible(v); if (tbl!=null) tbl.setDisable(v); }
  private void alert(String m){ new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait(); }
  private void info(String m){ new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait(); }
}
