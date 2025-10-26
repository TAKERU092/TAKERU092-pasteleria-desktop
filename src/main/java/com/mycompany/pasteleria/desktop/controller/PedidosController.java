// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/PedidosController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/* ============================================================
 * PEDIDOS (vista principal)
 *  - Tabla: N°, Cliente(nombre+apellido), Fecha pedido, Fecha entrega, Hora entrega, Estado.
 *  - Multifiltro de estados (varios a la vez). Default: APROBADO, SERVIDO, NO_ENCONTRADO.
 *  - Buscar por N° (exacto, server) o por nombre/apellido (client-side).
 *  - Acciones:
 *      Enviar a cocina  → EN_COCINA
 *      Asignar delivery → diálogo AsignarDelivery.fxml, inserta en delivery_asignacion y pone ASIGNADO
 *  - No duplica datos del cliente; sólo se muestran (JOIN).
 * ============================================================ */
public class PedidosController {

  // ---------- UI ----------
  @FXML private TextField txtBuscar;
  @FXML private MenuButton btnEstados;

  @FXML private TableView<Row> tbl;
  @FXML private TableColumn<Row, String> colNum, colCliente, colFecPed, colFecEnt, colHorEnt, colEstado;

  @FXML private Label lblRango;
  @FXML private ProgressIndicator loader;

  @FXML private Button btnBuscar, btnRefrescar, btnPrev, btnNext, btnToCocina, btnAsignar;

  // ---------- Estado ----------
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  private int limit = 20;
  private int offset = 0;
  private int total  = 0;

  private String filtroTexto = "";                 // N° o nombre cliente
  private final LinkedHashSet<String> estados = new LinkedHashSet<>(); // multi-selección

  private Task<List<Map<String,Object>>> consultaTask;
  private Task<Void> patchTask;

  // ---------- Row de la tabla ----------
  public static class Row {
    private final String num, cliente, fecPed, fecEnt, horEnt, estado;
    private final String direccion, distrito, referencia, telefono; // para el diálogo
    public Row(String num, String cliente, String fecPed, String fecEnt, String horEnt, String estado,
               String direccion, String distrito, String referencia, String telefono) {
      this.num=num; this.cliente=cliente; this.fecPed=fecPed; this.fecEnt=fecEnt; this.horEnt=horEnt; this.estado=estado;
      this.direccion=direccion; this.distrito=distrito; this.referencia=referencia; this.telefono=telefono;
    }
    public String getNum(){ return num; } public String getCliente(){ return cliente; }
    public String getFecPed(){ return fecPed; } public String getFecEnt(){ return fecEnt; }
    public String getHorEnt(){ return horEnt; } public String getEstado(){ return estado; }
    public String getDireccion(){ return direccion; } public String getDistrito(){ return distrito; }
    public String getReferencia(){ return referencia; } public String getTelefono(){ return telefono; }
  }

  // ================== INIT ==================
  @FXML
  public void initialize() {
    // Columnas
    colNum.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getNum()));
    colCliente.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getCliente()));
    colFecPed.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getFecPed()));
    colFecEnt.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getFecEnt()));
    colHorEnt.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getHorEnt()));
    colEstado.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getEstado()));

    // Multi-filtro: construir menú de estados
    buildEstadosMenu(List.of(
        "APROBADO","SERVIDO","NO_ENCONTRADO",
        "EN_COCINA","ASIGNADO","ENVIADO","ENTREGADO","CANCELADO"
    ));
    // Selección por defecto
    estados.addAll(List.of("APROBADO","SERVIDO","NO_ENCONTRADO"));
    markMenuStates();

    // Enter para buscar
    if (txtBuscar != null) txtBuscar.setOnAction(e -> buscar());

    refrescar();
  }

  private void buildEstadosMenu(List<String> opciones) {
    btnEstados.getItems().clear();
    for (String e : opciones) {
      CheckMenuItem it = new CheckMenuItem(e);
      it.setOnAction(ev -> {
        if (it.isSelected()) estados.add(e); else estados.remove(e);
        consultar();
      });
      btnEstados.getItems().add(it);
    }
  }
  private void markMenuStates() {
    for (var it : btnEstados.getItems()) {
      if (it instanceof CheckMenuItem c) c.setSelected(estados.contains(c.getText()));
    }
    btnEstados.setText(estados.isEmpty()? "Filtrar estados" : "Estados ("+estados.size()+")");
  }

  // ================== Acciones básicas ==================
  @FXML public void buscar(){ filtroTexto = s(txtBuscar.getText()); offset=0; consultar(); }
  @FXML public void refrescar(){ offset=0; consultar(); }
  @FXML public void paginaAnterior(){ if (offset - limit >= 0) { offset -= limit; consultar(); } }
  @FXML public void paginaSiguiente(){ if (offset + limit < total) { offset += limit; consultar(); } }

  // ================== Consulta a Supabase ==================
  private void consultar() {
    cancelar(consultaTask);
    setLoading(true);

    // SELECT con embed de cliente (nombre, apellido, telefono)
    String select = "id_pedido,fecha_pedido,fecha_entrega,hora_entrega,estado," +
                    "direccion,distrito,referencia," +
                    "cliente:cliente(nombre,apellido,telefono)";

    StringBuilder path = new StringBuilder("/pedidos?select=").append(enc(select))
        .append("&order=").append(enc("fecha_pedido.desc"))
        .append("&limit=").append(limit)
        .append("&offset=").append(offset);

    // Estados: estado=in.(A,B,C)
    if (!estados.isEmpty()) {
      String in = estados.stream().map(this::encRaw).collect(Collectors.joining(","));
      path.append("&estado=in.(").append(in).append(")");
    }

    // Búsqueda: num → id_pedido; texto → por nombre en memoria
    String term = s(filtroTexto).trim();
    boolean termNum = !term.isBlank() && term.chars().allMatch(Character::isDigit);
    if (termNum) path.append("&id_pedido=eq.").append(term);

    consultaTask = new Task<>() {
      @Override protected List<Map<String, Object>> call() throws Exception {
        var resp = api.getRespWithCount(path.toString());
        int code = resp.statusCode();
        String body = resp.body();
        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + "\n" + body);

        String cr = resp.headers().firstValue("Content-Range").orElse("");
        total = parseTotal(cr).orElse(0);

        List<Map<String,Object>> list = om.readValue(body, new TypeReference<List<Map<String,Object>>>(){});
        if (!term.isBlank() && !termNum) {
          String t = term.toLowerCase();
          list = list.stream().filter(m -> {
            Map<String,Object> c = (Map<String,Object>) m.get("cliente");
            String full = ((c!=null? s(c.get("nombre")) : "") + " " + (c!=null? s(c.get("apellido")) : "")).toLowerCase().trim();
            return full.contains(t);
          }).collect(Collectors.toList());
        }
        return list;
      }
    };

    consultaTask.setOnSucceeded(e -> {
      pintar(consultaTask.getValue());
      setLoading(false);
      markMenuStates();
    });
    consultaTask.setOnFailed(e -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(consultaTask.getException()).orElse(new RuntimeException("Error desconocido"));
      alert("No se pudo cargar pedidos.\n" + ex.getMessage());
    });
    new Thread(consultaTask,"pedidos-consulta").start();
  }

  private void pintar(List<Map<String,Object>> datos) {
    var rows = datos.stream().map(m -> {
      String id = String.valueOf(m.get("id_pedido"));
      Map<String,Object> c = (Map<String,Object>) m.get("cliente");
      String full = ((c!=null? s(c.get("nombre")) : "") + " " + (c!=null? s(c.get("apellido")) : "")).trim();
      String tel  = (c!=null? s(c.get("telefono")) : "");
      String fp   = s(m.get("fecha_pedido"));
      String fe   = s(m.get("fecha_entrega"));
      String he   = s(m.get("hora_entrega"));
      String est  = s(m.get("estado"));
      String dir  = s(m.get("direccion"));
      String dis  = s(m.get("distrito"));
      String ref  = s(m.get("referencia"));
      return new Row(
          id,
          full.isBlank()? "—" : full,
          fechaBonita(fp),
          fechaBonita(fe),
          horaBonita(he),
          est.isBlank()? "—" : est,
          dir, dis, ref, tel
      );
    }).collect(Collectors.toList());

    tbl.setItems(FXCollections.observableArrayList(rows));

    int from = total==0? 0 : offset+1;
    int to   = Math.min(offset+limit, Math.max(total, offset+rows.size()));
    if (lblRango != null) lblRango.setText("Mostrando " + from + "–" + to + " de " + total);
  }

  // ================== Acciones de estado ==================
  @FXML
  public void toEnCocina() { cambiarEstadoSeleccion("EN_COCINA"); }

  private void cambiarEstadoSeleccion(String nuevo) {
    Row r = tbl.getSelectionModel().getSelectedItem();
    if (r == null) { alert("Selecciona un pedido."); return; }
    cancelar(patchTask);
    setLoading(true);

    patchTask = new Task<>() {
      @Override protected Void call() throws Exception {
        var resp = api.patchJson("/pedidos?id_pedido=eq." + r.getNum(), "{\"estado\":\""+nuevo+"\"}");
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
          throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());
        return null;
      }
    };
    patchTask.setOnSucceeded(e -> { info("Pedido → "+nuevo); consultar(); });
    patchTask.setOnFailed(e -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(patchTask.getException()).orElse(new RuntimeException("Error desconocido"));
      alert("No se pudo cambiar estado.\n" + ex.getMessage());
    });
    new Thread(patchTask,"pedido-estado").start();
  }

  // ================== Asignar delivery (diálogo) ==================
  @FXML
  public void asignarDelivery() {
    Row r = tbl.getSelectionModel().getSelectedItem();
    if (r == null) { alert("Selecciona un pedido."); return; }

    try {
      var url = getClass().getResource("/com/mycompany/pasteleria/desktop/view/AsignarDelivery.fxml");
      if (url == null) throw new IllegalStateException("Falta AsignarDelivery.fxml");

      FXMLLoader loader = new FXMLLoader(url);
      Parent root = loader.load();
      AsignarDeliveryController ctrl = loader.getController();

      // Pasamos datos de cliente/dirección para mostrar
      ctrl.setPedidoData(
          Integer.parseInt(r.getNum()),
          r.getCliente(), r.getTelefono(),
          r.getDireccion(), r.getDistrito(), r.getReferencia()
      );

      Dialog<ButtonType> dlg = new Dialog<>();
      dlg.setTitle("Asignar delivery");
      dlg.getDialogPane().setContent(root);

      dlg.showAndWait();

      // Leer resultado desde la ventana del propio diálogo
      var win = dlg.getDialogPane().getScene().getWindow();
      var props = win.getProperties();
      boolean ok = Boolean.TRUE.equals(props.get("result_ok"));
      if (!ok) return;

      int idPedido   = (int) props.get("id_pedido");
      int idDelivery = (int) props.get("id_delivery");
      String hora    = String.valueOf(props.get("hora_salida"));

      cancelar(patchTask);
      setLoading(true);

      patchTask = new Task<>() {
        @Override protected Void call() throws Exception {
          // 1) Inserta asignación normalizada
          String asignJson = String.format(Locale.ROOT,
              "{\"id_pedido\": %d, \"id_delivery\": %d, \"hora_salida\": \"%s\", \"estado\": \"ASIGNADO\"}",
              idPedido, idDelivery, hora);
          var respIns = api.postJson("/delivery_asignacion", asignJson);
          if (respIns.statusCode()<200 || respIns.statusCode()>=300)
            throw new RuntimeException("Asignación HTTP "+respIns.statusCode()+"\n"+respIns.body());

          // 2) Marca pedido como ASIGNADO
          var respPed = api.patchJson("/pedidos?id_pedido=eq."+idPedido, "{\"estado\":\"ASIGNADO\"}");
          if (respPed.statusCode()<200 || respPed.statusCode()>=300)
            throw new RuntimeException("Pedido HTTP "+respPed.statusCode()+"\n"+respPed.body());
          return null;
        }
      };
      patchTask.setOnSucceeded(e -> { info("Pedido asignado."); consultar(); });
      patchTask.setOnFailed(e -> {
        setLoading(false);
        Throwable ex = Optional.ofNullable(patchTask.getException()).orElse(new RuntimeException("Error desconocido"));
        alert("No se pudo asignar delivery.\n" + ex.getMessage());
      });
      new Thread(patchTask,"asignar-delivery").start();

    } catch (Exception ex) {
      alert("No se pudo abrir el diálogo.\n"+ex.getMessage());
    }
  }

  // ================== Helpers ==================
  private void setLoading(boolean v){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> setLoading(v)); return; }
    if (loader != null) loader.setVisible(v);
    if (tbl != null) tbl.setDisable(v);
    if (txtBuscar!=null) txtBuscar.setDisable(v);
    if (btnBuscar!=null) btnBuscar.setDisable(v);
    if (btnRefrescar!=null) btnRefrescar.setDisable(v);
    if (btnPrev!=null) btnPrev.setDisable(v);
    if (btnNext!=null) btnNext.setDisable(v);
    if (btnToCocina!=null) btnToCocina.setDisable(v);
    if (btnAsignar!=null) btnAsignar.setDisable(v);
    if (btnEstados!=null) btnEstados.setDisable(v);
  }

  private static String s(Object x){ return x==null? "" : String.valueOf(x); }

  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }
  private String encRaw(String x){ return x; } // tokens simples para in.(...)

  private static Optional<Integer> parseTotal(String contentRange) {
    try {
      if (contentRange==null || !contentRange.contains("/")) return Optional.empty();
      String after = contentRange.substring(contentRange.indexOf('/')+1).trim();
      return Optional.of(Integer.parseInt(after));
    } catch(Exception e){ return Optional.empty(); }
  }

  // "12 oct", "02 feb" (sin año). Acepta "YYYY-MM-DD" o ISO "YYYY-MM-DDTHH:mm:ss"
  private static final String[] MES = {"ene","feb","mar","abr","may","jun","jul","ago","sep","oct","nov","dic"};
  private static String fechaBonita(String iso) {
    if (iso==null||iso.isBlank()) return "—";
    String s = iso;
    int t = s.indexOf('T'); if (t>0) s = s.substring(0,t);
    if (s.length()<10) return s;
    String dd = s.substring(8,10);
    int m = 1;
    try { m = Integer.parseInt(s.substring(5,7)); } catch(Exception ignored){ }
    return dd + " " + MES[Math.max(1,Math.min(12,m))-1];
  }
  private static String horaBonita(String time) {
    if (time==null||time.isBlank()) return "—";
    if (time.length() >= 5) return time.substring(0,5); // HH:mm:ss → HH:mm
    try { return LocalTime.parse(time).format(DateTimeFormatter.ofPattern("HH:mm")); }
    catch(Exception e){ return time; }
  }

  private void alert(String m){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> alert(m)); return; }
    new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait();
  }
  private void info(String m){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> info(m)); return; }
    new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait();
  }
  private static void cancelar(Task<?> t) { if (t!=null && t.isRunning()) t.cancel(true); }
}
