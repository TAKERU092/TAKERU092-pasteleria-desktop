// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/CocinaController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class CocinaController {

  // IZQ: cards
  @FXML private TilePane grid;

  // DER: resumen + acciones
  @FXML private Label lblPedido, lblEstado, lblCliente, lblFecPed, lblFecEnt, lblHorEnt, lblTotalPedido;
  @FXML private TextArea txtComentarios;
  @FXML private Button btnEnPrep, btnServido;

  // Detalle (2 columnas)
  @FXML private TableView<RowDetalle> tblDetalle;
  @FXML private TableColumn<RowDetalle, String> colProd, colCant;

  @FXML private Button btnRefrescar;
  @FXML private ProgressIndicator loader;

  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  private List<Map<String,Object>> pedidos = List.of();
  private Integer idSeleccionado = null;
  private final Map<Integer, Node> cardById = new HashMap<>();

  private Task<?> loadPedidosTask, loadDetalleTask, patchTask;

  public static class RowDetalle {
    private final String producto, cantidad;
    public RowDetalle(String p, String c){ this.producto=p; this.cantidad=c; }
    public String getProducto(){ return producto; }
    public String getCantidad(){ return cantidad; }
  }

  @FXML
  public void initialize() {
    // Ajuste anti “3ª columna”
    tblDetalle.getColumns().setAll(colProd, colCant);
    tblDetalle.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

    // TilePane: cada tile (card) 260 px y alineado arriba-izquierda
    grid.setTileAlignment(Pos.TOP_LEFT);
    grid.setPrefTileWidth(260);   // ancho deseado por card
    grid.setPrefTileHeight(TilePane.USE_COMPUTED_SIZE);

    if (btnRefrescar != null) btnRefrescar.setOnAction(e -> refrescar());
    if (btnEnPrep   != null) btnEnPrep.setOnAction(e -> cambiarEstado("EN_PREPARACION"));
    if (btnServido  != null) btnServido.setOnAction(e -> cambiarEstado("SERVIDO"));

    refrescar();
  }

  /* Acciones desde FXML */
  @FXML private void toEnPreparacion(){ cambiarEstado("EN_PREPARACION"); }
  @FXML private void toServido(){       cambiarEstado("SERVIDO"); }
  @FXML public void refrescar(){ cargarPedidos(); }

  /* Cargas */
  private void cargarPedidos() {
    cancelar(loadPedidosTask);
    setLoading(true);

    String select = enc("id_pedido,fecha_pedido,fecha_entrega,hora_entrega,estado,total,comentarios,cliente:cliente(nombre,apellido)");
    String path = "/pedidos?select=" + select +
        "&or=(" + enc("estado.eq.EN_COCINA,estado.eq.EN_PREPARACION") + ")" +
        "&order=fecha_pedido.asc&limit=200";

    loadPedidosTask = new Task<>() {
      @Override protected List<Map<String, Object>> call() throws Exception {
        var resp = api.getResp(path);
        if (resp.statusCode()<200 || resp.statusCode()>=300)
          throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());
        return om.readValue(resp.body(), new TypeReference<List<Map<String,Object>>>(){});
      }
    };

    loadPedidosTask.setOnSucceeded(e -> {
      pedidos = (List<Map<String, Object>>) ((Task<?>) e.getSource()).getValue();
      renderCards(pedidos);
      idSeleccionado = null;
      pintarEncabezado(null);
      tblDetalle.getItems().clear();
      lblTotalPedido.setText("—");
      setLoading(false);
    });
    loadPedidosTask.setOnFailed(e -> {
      setLoading(false);
      showError("No se pudo cargar pedidos.\n" +
          (e.getSource().getException()!=null? e.getSource().getException().getMessage() : ""));
    });

    new Thread(loadPedidosTask, "cocina-load").start();
  }

  private void cargarDetalle(int idPedido) {
    cancelar(loadDetalleTask);
    setLoading(true);

    String select = enc("id_detalle,id_pedido,id_producto,cantidad,producto:productos(nombre)");
    String path = "/detalle_pedido?select=" + select + "&id_pedido=eq." + idPedido + "&order=id_detalle.asc";

    loadDetalleTask = new Task<List<RowDetalle>>() {
      @Override protected List<RowDetalle> call() throws Exception {
        var resp = api.getResp(path);
        if (resp.statusCode()<200 || resp.statusCode()>=300)
          throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());
        List<Map<String,Object>> dets = om.readValue(resp.body(), new TypeReference<List<Map<String,Object>>>(){});
        return dets.stream().map(d -> {
          Map<String,Object> prod = asMap(d.get("producto"));
          String nombre = s(prod.get("nombre"));
          String cant   = stripZeros(parseBD(d.get("cantidad")));
          return new RowDetalle(nombre.isBlank()? "—" : nombre, cant);
        }).collect(Collectors.toList());
      }
    };

    loadDetalleTask.setOnSucceeded(e -> {
      @SuppressWarnings("unchecked")
      var rows = (List<RowDetalle>) ((Task<?>) e.getSource()).getValue();
      tblDetalle.setItems(FXCollections.observableArrayList(rows));
      Map<String,Object> mp = buscar(idPedido);
      if (mp != null) lblTotalPedido.setText(money(parseBD(mp.get("total"))));
      setLoading(false);
    });
    loadDetalleTask.setOnFailed(e -> {
      setLoading(false);
      showError("No se pudo cargar el detalle.\n" +
          (e.getSource().getException()!=null? e.getSource().getException().getMessage() : ""));
    });

    new Thread(loadDetalleTask, "cocina-detalle").start();
  }

  /* Cards */
  private void renderCards(List<Map<String,Object>> data) {
    grid.getChildren().clear();
    cardById.clear();

    for (Map<String,Object> m : data) {
      int id = Integer.parseInt(String.valueOf(m.get("id_pedido")));
      Node card = buildCard(m);
      cardById.put(id, card);
      grid.getChildren().add(card);
    }
  }

  private Node buildCard(Map<String,Object> m){
    int id = Integer.parseInt(String.valueOf(m.get("id_pedido")));
    String estado = s(m.get("estado"));
    Map<String,Object> c = asMap(m.get("cliente"));
    String cliente = (s(c.get("nombre")) + " " + s(c.get("apellido"))).trim();

    VBox box = new VBox(6);
    box.getStyleClass().add("card");
    box.setPadding(new Insets(10));
    // === ancho mínimo y preferido por card ===
    box.setMinWidth(260);
    box.setPrefWidth(260);

    Label lNum = new Label("#" + id);
    lNum.setStyle("-fx-font-weight:700;");

    Label lEst = new Label(estado);
    lEst.getStyleClass().addAll("badge", cssBadgeFor(estado));

    var spacer = new Pane();
    HBox head = new HBox(8, lNum, spacer, lEst);
    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

    Label lCli = new Label(cliente.isBlank()? "—" : cliente);
    lCli.setWrapText(true);

    box.getChildren().addAll(head, lCli);
    box.setOnMouseClicked(ev -> selectCard(id));
    return box;
  }

  private void selectCard(int id){
    idSeleccionado = id;
    pintarEncabezado(buscar(id));
    cargarDetalle(id);
  }

  /* Cambiar estado */
  private void cambiarEstado(String nuevo) {
    if (idSeleccionado == null) { showError("Selecciona un pedido."); return; }
    cancelar(patchTask);
    setLoading(true);

    int id = idSeleccionado;
    patchTask = new Task<>() {
      @Override protected Void call() throws Exception {
        String body = "{\"estado\":\""+nuevo+"\"}";
        var resp = api.patchJson("/pedidos?id_pedido=eq." + id, body);
        if (resp.statusCode()==200 || resp.statusCode()==204) return null;
        throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());
      }
    };
    patchTask.setOnSucceeded(e -> { info("Pedido " + id + " → " + nuevo); refrescar(); });
    patchTask.setOnFailed(e -> { setLoading(false); showError("No se pudo actualizar.\n" +
        (e.getSource().getException()!=null? e.getSource().getException().getMessage() : "")); });
    new Thread(patchTask,"cocina-patch").start();
  }

  /* Encabezado */
  private void pintarEncabezado(Map<String,Object> m){
    if (m == null) {
      lblPedido.setText("—"); lblEstado.setText("—"); lblEstado.getStyleClass().removeIf(c -> c.startsWith("badge--"));
      lblCliente.setText("—"); lblFecPed.setText("—"); lblFecEnt.setText("—"); lblHorEnt.setText("—");
      txtComentarios.setText(""); lblTotalPedido.setText("—"); return;
    }
    String est = s(m.get("estado"));
    Map<String,Object> c = asMap(m.get("cliente"));
    String cliente = (s(c.get("nombre")) + " " + s(c.get("apellido"))).trim();

    lblPedido.setText(String.valueOf(m.get("id_pedido")));
    lblEstado.setText(est);
    lblEstado.getStyleClass().removeIf(cn -> cn.startsWith("badge--"));
    lblEstado.getStyleClass().addAll("badge", cssBadgeFor(est));
    lblCliente.setText(cliente.isBlank()? "—" : cliente);
    lblFecPed.setText(fechaBonita(s(m.get("fecha_pedido"))));
    lblFecEnt.setText(fechaBonita(s(m.get("fecha_entrega"))));
    lblHorEnt.setText(horaBonita(s(m.get("hora_entrega"))));
    txtComentarios.setText(s(m.get("comentarios")));
    lblTotalPedido.setText(money(parseBD(m.get("total"))));
  }

  /* Helpers básicos */
  private Map<String,Object> buscar(int id){
    for (var m : pedidos) if (String.valueOf(m.get("id_pedido")).equals(String.valueOf(id))) return m;
    return null;
  }
  private void setLoading(boolean v){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> setLoading(v)); return; }
    if (loader != null) loader.setVisible(v);
    if (grid != null) grid.setDisable(v);
    if (tblDetalle != null) tblDetalle.setDisable(v);
    if (btnEnPrep != null) btnEnPrep.setDisable(v);
    if (btnServido != null) btnServido.setDisable(v);
    if (btnRefrescar != null) btnRefrescar.setDisable(v);
  }

  private static String s(Object x){ return x==null? "" : String.valueOf(x); }
  private static Map<String,Object> asMap(Object o){ return (o instanceof Map<?,?> m)? (Map<String,Object>) m : Map.of(); }
  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }
  private static BigDecimal parseBD(Object v){
    try { if (v==null) return BigDecimal.ZERO; if (v instanceof BigDecimal bd) return bd;
      String s = String.valueOf(v).replace(',','.');
      return s.isBlank()? BigDecimal.ZERO : new BigDecimal(s);
    } catch(Exception e){ return BigDecimal.ZERO; }
  }
  private static String stripZeros(BigDecimal v){ return v==null? "0" : v.stripTrailingZeros().toPlainString(); }
  private static String money(BigDecimal v){ if (v==null) v = BigDecimal.ZERO; return "S/ " + v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
  private static String fechaBonita(String iso){
    if (iso==null||iso.isBlank()) return "—";
    String s = iso; int t = s.indexOf('T'); if (t>0) s = s.substring(0,t);
    if (s.length()<10) return s; String dd = s.substring(8,10);
    int m = 1; try { m = Integer.parseInt(s.substring(5,7)); } catch(Exception ignored){}
    String[] MES = {"ene","feb","mar","abr","may","jun","jul","ago","sep","oct","nov","dic"};
    return dd + " " + MES[Math.max(1,Math.min(12,m))-1];
  }
  private static String horaBonita(String time){ if (time==null||time.isBlank()) return "—"; return time.length()>=5? time.substring(0,5) : time; }
  private void showError(String m){ if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(m)); return; } new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait(); }
  private void info(String m){ if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> info(m)); return; } new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait(); }

  private static String cssBadgeFor(String estado){
    if (estado == null) return "badge--gris";
    return switch (estado) {
      case "EN_COCINA"      -> "badge--naranja";
      case "EN_PREPARACION" -> "badge--azul";
      case "SERVIDO"        -> "badge--verde";
      default               -> "badge--gris";
    };
  }
  private static void cancelar(Task<?> t){ if (t!=null && t.isRunning()) t.cancel(true); }
}
