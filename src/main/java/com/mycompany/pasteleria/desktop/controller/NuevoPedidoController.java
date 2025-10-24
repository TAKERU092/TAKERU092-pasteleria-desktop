// src/main/java/com/mycompany/pasteleria/desktop/controller/NuevoPedidoController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class NuevoPedidoController {

  @FXML private TextField txtIdCliente, txtFechaEnt, txtHoraEnt;
  @FXML private TableView<Row> tblItems;
  @FXML private TableColumn<Row, String> colProd, colCant, colPrecio, colSub;
  @FXML private Label lblTotal;

  private final List<Row> items = new ArrayList<>();

  public static class Row {
    private final String producto;
    private final String cantidad;
    private final String precio; // unitario
    public Row(String p, String c, String pr){ this.producto=p; this.cantidad=c; this.precio=pr; }
    public String getProducto(){ return producto; }
    public String getCantidad(){ return cantidad; }
    public String getPrecio(){ return precio; }
    public String getSub(){
      try {
        BigDecimal pu = new BigDecimal(precio.replace(",", "."));
        BigDecimal ca = new BigDecimal(cantidad);
        return money(pu.multiply(ca));
      } catch(Exception e){ return money(BigDecimal.ZERO); }
    }
    private static String money(BigDecimal v){ return "S/ " + v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
  }

  @FXML
  public void initialize() {
    colProd.setCellValueFactory(new PropertyValueFactory<>("producto"));
    colCant.setCellValueFactory(new PropertyValueFactory<>("cantidad"));
    colPrecio.setCellValueFactory(new PropertyValueFactory<>("precio"));
    colSub.setCellValueFactory(new PropertyValueFactory<>("sub"));

    tblItems.setItems(FXCollections.observableArrayList(items));
    recalcTotal();
  }

  @FXML
  public void addItem() {
    TextInputDialog d = new TextInputDialog();
    d.setTitle("Añadir ítem");
    d.setHeaderText(null);
    d.setContentText("Formato: producto|cantidad|precio_unitario");
    d.getEditor().setPromptText("Torta Selva Negra|1|45.00");
    var res = d.showAndWait();
    if (res.isEmpty()) return;
    String[] parts = res.get().split("\\|");
    if (parts.length < 3) {
      new Alert(Alert.AlertType.ERROR, "Formato inválido").showAndWait();
      return;
    }
    items.add(new Row(parts[0].trim(), parts[1].trim(), parts[2].trim()));
    tblItems.getItems().setAll(items);
    recalcTotal();
  }

  @FXML
  public void removeItem() {
    int idx = tblItems.getSelectionModel().getSelectedIndex();
    if (idx >= 0 && idx < items.size()) {
      items.remove(idx);
      tblItems.getItems().setAll(items);
      recalcTotal();
    }
  }

  private void recalcTotal() {
    BigDecimal total = BigDecimal.ZERO;
    for (Row r : items) {
      try {
        BigDecimal pu = new BigDecimal(r.getPrecio().replace(",", "."));
        BigDecimal ca = new BigDecimal(r.getCantidad());
        total = total.add(pu.multiply(ca));
      } catch(Exception ignore){}
    }
    lblTotal.setText("S/ " + total.setScale(2, RoundingMode.HALF_UP).toPlainString());
  }

  /** Construye JSON para insertar en /pedidos (estado=REGISTRADO) */
  public String buildPayload() throws Exception {
    var map = new java.util.LinkedHashMap<String,Object>();
    map.put("id_cliente", parseInt(txtIdCliente.getText()));
    map.put("fecha_pedido", nowIso()); // o que lo ponga la BD por default
    map.put("fecha_entrega", blankToNull(txtFechaEnt.getText()));
    map.put("hora_entrega", blankToNull(normalizeTime(txtHoraEnt.getText())));
    map.put("estado", "REGISTRADO");
    map.put("total", extractTotal());
    return new ObjectMapper().writeValueAsString(java.util.List.of(map)); // array para bulk insert
  }

  /** Construye JSON array para /detalle_pedido. Reemplazamos id_pedido luego. */
  public String buildDetallesArray() throws Exception {
    var list = new java.util.ArrayList<java.util.Map<String,Object>>();
    for (Row r : items) {
      var m = new java.util.LinkedHashMap<String,Object>();
      m.put("id_pedido", "__ID_PEDIDO__"); // marcador para reemplazar
      m.put("producto", r.getProducto());
      m.put("cantidad", parseInt(r.getCantidad()));
      m.put("precio_unitario", normalizeMoney(r.getPrecio()));
      list.add(m);
    }
    return new ObjectMapper().writeValueAsString(list);
  }

  private static Integer parseInt(String s){
    try { return (s==null || s.isBlank())? null : Integer.parseInt(s.trim()); }
    catch(Exception e){ return null; }
  }
  private static String blankToNull(String s){ return (s==null || s.isBlank())? null : s.trim(); }
  private static String normalizeTime(String s){
    if (s==null || s.isBlank()) return null;
    String t = s.trim();
    if (t.length()==5) t = t + ":00";
    return t;
  }
  private static String normalizeMoney(String s){
    if (s==null || s.isBlank()) return "0";
    return s.trim().replace(",", ".");
  }
  private static String extractTotalText(String t){ return t==null? "0" : t.replace("S/","").trim(); }
  private String extractTotal(){
    String t = extractTotalText(lblTotal.getText());
    try { new BigDecimal(t); return t; } catch(Exception e){ return "0"; }
  }
  private static String nowIso(){
    return java.time.LocalDateTime.now().toString(); // o usa OffsetDateTime si prefieres
  }
}
