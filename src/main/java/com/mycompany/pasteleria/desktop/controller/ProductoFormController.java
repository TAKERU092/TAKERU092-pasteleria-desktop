// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/ProductoFormController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.model.Categoria;
import com.mycompany.pasteleria.desktop.model.Producto;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;
import java.util.Map;

public class ProductoFormController {

  @FXML private TextField txtNombre, txtPrecio, txtStock, txtImagen;
  @FXML private TextArea  txtDescripcion;
  @FXML private ComboBox<Categoria> cbCategoria;
  @FXML private ComboBox<String> cbEstado;
  @FXML private Label lblError;

  private ApiClient api;
  private ObjectMapper om;
  private Producto editing;

  public void init(ApiClient api, ObjectMapper om, List<Categoria> categorias, Producto editing){
    this.api = api;
    this.om = om;
    this.editing = editing;
    cbCategoria.getItems().setAll(categorias);
    cbEstado.getItems().setAll("ACTIVO", "INACTIVO");
    if (editing != null){
      txtNombre.setText(editing.nombre);
      txtPrecio.setText(editing.getPrecioBD().toPlainString());
      txtStock.setText(String.valueOf(editing.stock == null ? 0 : editing.stock));
      txtImagen.setText(editing.imagen_url);
      txtDescripcion.setText(editing.descripcion);
      cbEstado.getSelectionModel().select(editing.estado == null ? "ACTIVO" : editing.estado);
      // seleccionar categoría
      if (editing.categoria != null){
        for (Categoria c : categorias){
          if (c != null && c.nombre != null && c.nombre.equalsIgnoreCase(editing.categoria.nombre)){
            cbCategoria.getSelectionModel().select(c); break;
          }
        }
      }
    } else {
      cbEstado.getSelectionModel().select("ACTIVO");
    }
  }

  @FXML
  private void initialize(){
    // El diálogo padre añadirá los botones OK/CANCEL. Aquí validamos al pulsar OK.
    // Agarra el botón OK cuando esté disponible
    Platform.runLater(() -> {
      DialogPane pane = (DialogPane) txtNombre.getScene().getRoot();
      Button ok = (Button) pane.lookupButton(ButtonType.OK);
      ok.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
        if (!save()){
          evt.consume(); // no cerrar
        }
      });
    });
  }

  private boolean save(){
    lblError.setText("");
    String nombre = val(txtNombre.getText());
    String precio = val(txtPrecio.getText()).replace(",", ".");
    String stockS = val(txtStock.getText());
    String estado = cbEstado.getValue();
    Categoria cat  = cbCategoria.getValue();
    String imagen  = val(txtImagen.getText());
    String desc    = val(txtDescripcion.getText());

    if (nombre.isBlank()){ lblError.setText("Nombre es requerido."); return false; }
    if (cat == null || cat.id_categoria == null){ lblError.setText("Selecciona una categoría."); return false; }
    if (precio.isBlank()){ lblError.setText("Precio es requerido."); return false; }
    try { Double.parseDouble(precio); } catch(Exception e){ lblError.setText("Precio inválido."); return false; }
    int stock = 0;
    try { stock = Integer.parseInt(stockS.isBlank()? "0" : stockS); } catch(Exception e){ lblError.setText("Stock inválido."); return false; }
    if (estado == null || estado.isBlank()) estado = "ACTIVO";

    String json = "{" +
        "\"nombre\":\""+esc(nombre)+"\"," +
        "\"id_categoria\":"+cat.id_categoria+"," +
        "\"precio\":\""+esc(precio)+"\"," +
        "\"stock\":"+stock+"," +
        "\"estado\":\""+esc(estado)+"\"," +
        "\"imagen_url\":\""+esc(imagen)+"\"," +
        "\"descripcion\":\""+esc(desc)+"\"" +
        "}";

    try {
      if (editing == null){
        var resp = api.postJson("/productos", "["+json+"]");
        if (resp.statusCode() < 200 || resp.statusCode() >= 300){
          lblError.setText("Error HTTP "+resp.statusCode()+": "+resp.body());
          return false;
        }
      } else {
        var resp = api.patchJson("/productos?id_producto=eq."+editing.id_producto, json);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300){
          lblError.setText("Error HTTP "+resp.statusCode()+": "+resp.body());
          return false;
        }
      }
      return true;
    } catch (Exception ex){
      lblError.setText(ex.getMessage());
      return false;
    }
  }

  private static String val(String s){ return s==null? "" : s.trim(); }
  private static String esc(String x){ return x == null ? "" : x.replace("\"","\\\""); }
}
