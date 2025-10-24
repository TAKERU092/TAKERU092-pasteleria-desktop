// src/main/java/com/mycompany/pasteleria/desktop/controller/CocinaController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.DetallePedido;
import com.mycompany.pasteleria.desktop.model.Pedido;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class CocinaController {

  // ---------- UI: lista de pedidos ----------
  @FXML private TableView<RowPedido> tblPedidos;
  @FXML private TableColumn<RowPedido, String> colNum, colCliente, colEstado, colEntrega, colTotal;

  // ---------- UI: detalle ----------
  @FXML private Label lblTituloDetalle, lblTotalPedido;
  @FXML private TableView<RowDetalle> tblDetalle;
  @FXML private TableColumn<RowDetalle, String> colProd, colCant, colPrecio, colSub;

  @FXML private ProgressIndicator loader;

  // (Opcional) botones para cambiar estado: declara en FXML con estos fx:id si los tienes
  @FXML private Button btnCocinando, btnCocinado, btnEntregado, btnCancelado;
  @FXML private Button btnRefrescar; // si tienes un botón de refrescar

  // ---------- Estado / servicios ----------
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();
  private List<Pedido> pedidos = List.of();
  private Integer pedidoSeleccionado = null;

  // Referencias a tareas en curso (para cancelar si fuese necesario)
  private Task<?> loadPedidosTask;
  private Task<?> loadDetalleTask;
  private Task<?> patchTask;

  // ---------- Row models (para TableView) ----------
  public static class RowPedido {
    private final String num, cliente, estado, entrega, total;
    public RowPedido(String n, String c, String e, String en, String t){ num=n; cliente=c; estado=e; entrega=en; total=t; }
    public String getNum(){ return num; } public String getCliente(){ return cliente; }
    public String getEstado(){ return estado; } public String getEntrega(){ return entrega; } public String getTotal(){ return total; }
  }
  public static class RowDetalle {
    private final String producto, cantidad, precio, subtotal;
    public RowDetalle(String p, String c, String pr, String s){ producto=p; cantidad=c; precio=pr; subtotal=s; }
    public String getProducto(){ return producto; } public String getCantidad(){ return cantidad; }
    public String getPrecio(){ return precio; } public String getSubtotal(){ return subtotal; }
  }

  // ---------- Ciclo de vida ----------
  @FXML
  public void initialize() {
    // columnas pedidos
    colNum.setCellValueFactory(new PropertyValueFactory<>("num"));
    colCliente.setCellValueFactory(new PropertyValueFactory<>("cliente"));
    colEstado.setCellValueFactory(new PropertyValueFactory<>("estado"));
    colEntrega.setCellValueFactory(new PropertyValueFactory<>("entrega"));
    colTotal.setCellValueFactory(new PropertyValueFactory<>("total"));

    // columnas detalle
    colProd.setCellValueFactory(new PropertyValueFactory<>("producto"));
    colCant.setCellValueFactory(new PropertyValueFactory<>("cantidad"));
    colPrecio.setCellValueFactory(new PropertyValueFactory<>("precio"));
    colSub.setCellValueFactory(new PropertyValueFactory<>("subtotal"));

    // selección por ÍTEM (no por índice; robusto frente a ordenamiento/filtrado)
    tblPedidos.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
      if (newRow != null) {
        try {
          pedidoSeleccionado = Integer.parseInt(newRow.getNum());
          cargarDetalle(pedidoSeleccionado);
        } catch (NumberFormatException ex) {
          pedidoSeleccionado = null;
        }
      }
    });

    // carga inicial
    refrescar();
  }

  // ---------- Acciones ----------
  @FXML public void refrescar() { cargarPedidos(); }
  @FXML public void toCocinando(){ cambiarEstado("COCINANDO"); }
  @FXML public void toCocinado(){  cambiarEstado("COCINADO"); }
  @FXML public void toEntregado(){ cambiarEstado("ENTREGADO"); }
  @FXML public void toCancelado(){ cambiarEstado("CANCELADO"); }

  // ---------- Cargas ----------
  private void cargarPedidos() {
    cancelar(loadPedidosTask); // evita carreras si el usuario pulsa varias veces
    setLoading(true);

    String select = enc("id_pedido,id_cliente,fecha_pedido,fecha_entrega,hora_entrega,estado,total,cliente:cliente(nombre,apellido)");
    String path = "/pedidos?select=" + select + "&or=(" + enc("estado.eq.COCINA,estado.eq.COCINANDO") + ")"
        + "&order=fecha_pedido.asc&limit=50";

    loadPedidosTask = new Task<List<RowPedido>>() {
      @Override protected List<RowPedido> call() throws Exception {
        var resp = api.getResp(path);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
          throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());

        pedidos = om.readValue(resp.body(), new TypeReference<List<Pedido>>(){});
        return pedidos.stream().map(p -> new RowPedido(
            String.valueOf(p.id_pedido),
            p.cliente != null ? ((s(p.cliente.nombre) + " " + s(p.cliente.apellido)).trim()) : "—",
            s(p.estado),
            (s(p.fecha_entrega) + " " + s(p.hora_entrega)).trim(),
            s(p.total)
        )).collect(Collectors.toList());
      }
    };

    loadPedidosTask.setOnSucceeded(ev -> {
      tblPedidos.setItems(FXCollections.observableArrayList(((Task<List<RowPedido>>) ev.getSource()).getValue()));
      lblTituloDetalle.setText("Detalle del pedido —");
      tblDetalle.getItems().clear();
      lblTotalPedido.setText("—");
      setLoading(false);
    });

    loadPedidosTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(ev.getSource().getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo cargar pedidos.\n" + ex.getMessage());
    });

    new Thread(loadPedidosTask, "cocina-load-pedidos").start();
  }

  private void cargarDetalle(int idPedido) {
    cancelar(loadDetalleTask);
    setLoading(true);

    String select = enc("id_detalle,id_pedido,id,producto,cantidad,precio_unitario");
    String path = "/detalle_pedido?select=" + select + "&id_pedido=eq." + idPedido + "&order=id_detalle.asc";

    loadDetalleTask = new Task<List<RowDetalle>>() {
      @Override protected List<RowDetalle> call() throws Exception {
        var resp = api.getResp(path);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
          throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());

        var detalles = om.readValue(resp.body(), new TypeReference<List<DetallePedido>>(){});

        BigDecimal total = BigDecimal.ZERO;
        List<RowDetalle> rows = new ArrayList<>();
        for (DetallePedido d : detalles) {
          BigDecimal pu   = parseMoney(d.precio_unitario);
          BigDecimal cant = toBD(d.cantidad);
          BigDecimal sub  = pu.multiply(cant);
          total = total.add(sub);
          rows.add(new RowDetalle(
              s(d.producto),
              cant.stripTrailingZeros().toPlainString(),
              money(pu),
              money(sub)
          ));
        }

        // Llevar el total calculado a Thread FX a través de valor de mensaje (opcional)
        updateMessage(total.toPlainString());
        return rows;
      }
    };

    loadDetalleTask.setOnSucceeded(ev -> {
      @SuppressWarnings("unchecked")
      Task<List<RowDetalle>> t = (Task<List<RowDetalle>>) ev.getSource();
      tblDetalle.setItems(FXCollections.observableArrayList(t.getValue()));
      // total desde message(); si es null, recalcular en FX (seguro pero más costoso)
      String totalStr = Optional.ofNullable(t.getMessage()).orElse("0");
      lblTituloDetalle.setText("Detalle del pedido — " + idPedido);
      lblTotalPedido.setText(money(new BigDecimal(totalStr)));
      setLoading(false);
    });

    loadDetalleTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(ev.getSource().getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo cargar detalle.\n" + ex.getMessage());
    });

    new Thread(loadDetalleTask, "cocina-load-detalle").start();
  }

  // ---------- Cambios de estado ----------
  private void cambiarEstado(String nuevo) {
    if (pedidoSeleccionado == null) { showError("Selecciona un pedido de la lista."); return; }

    cancelar(patchTask);
    setLoading(true);

    patchTask = new Task<Void>() {
      @Override protected Void call() throws Exception {
        String body = "{\"estado\":\""+nuevo+"\"}";
        var resp = api.patchJson("/pedidos?id_pedido=eq." + pedidoSeleccionado, body);

        // PostgREST puede devolver 200 (con representación) o 204 (sin cuerpo)
        if (resp.statusCode() == 200 || resp.statusCode() == 204) return null;
        throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());
      }
    };

    patchTask.setOnSucceeded(ev -> {
      info("Estado actualizado a " + nuevo);
      // Vuelve a cargar lista y limpia detalle (evita parpadeos manteniendo selección si quieres)
      cargarPedidos();
    });

    patchTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(ev.getSource().getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo actualizar.\n" + ex.getMessage());
    });

    new Thread(patchTask, "cocina-patch").start();
  }

  // ---------- Utils ----------
  private void setLoading(boolean v){
    // Solo ejecutar en FX thread
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(() -> setLoading(v));
      return;
    }
    loader.setVisible(v);
    if (tblPedidos != null) tblPedidos.setDisable(v);
    if (tblDetalle != null) tblDetalle.setDisable(v);

    // Deshabilita acciones mientras hay I/O
    if (btnCocinando != null) btnCocinando.setDisable(v);
    if (btnCocinado  != null) btnCocinado.setDisable(v);
    if (btnEntregado != null) btnEntregado.setDisable(v);
    if (btnCancelado != null) btnCancelado.setDisable(v);
    if (btnRefrescar != null) btnRefrescar.setDisable(v);
  }

  private static String s(String x){ return x==null? "" : x; }

  private static String money(BigDecimal v){
    if (v == null) v = BigDecimal.ZERO;
    return "S/ " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static BigDecimal parseMoney(String s){
    try {
      String norm = (s==null || s.isBlank()) ? "0" : s.replace(",", ".");
      return new BigDecimal(norm);
    } catch(Exception e){
      return BigDecimal.ZERO;
    }
  }

  // Convierte números a BigDecimal de forma segura (evita binario flotante)
  private static BigDecimal toBD(Object n) {
    if (n == null) return BigDecimal.ZERO;
    if (n instanceof BigDecimal bd) return bd;
    return new BigDecimal(String.valueOf(n));
  }

  private void showError(String m){
    // Siempre en FX thread
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(() -> showError(m));
      return;
    }
    new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait();
  }

  private void info(String m){
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(() -> info(m));
      return;
    }
    new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait();
  }

  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }

  private static void cancelar(Task<?> t) {
    if (t != null && t.isRunning()) {
      t.cancel(true);
    }
  }
}

