// src/main/java/com/mycompany/pasteleria/desktop/controller/PedidosController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.Pedido;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class PedidosController {

  // ---------- UI ----------
  @FXML private TextField txtBuscar;
  @FXML private ComboBox<String> cbEstado;
  @FXML private TableView<Row> tbl;
  @FXML private TableColumn<Row, String> colNum, colCliente, colFecPed, colFecEnt, colHorEnt, colEstado, colTotal;
  @FXML private Label lblTotal, lblPagina, lblFiltro, lblEstado, lblRango;
  @FXML private ProgressIndicator loader;

  // (opcionales si existen en tu FXML)
  @FXML private Button btnBuscar, btnRefrescar, btnPrev, btnNext, btnExport;
  @FXML private Button btnToCocina, btnToCocinando, btnToCocinado, btnToEntregado, btnToCancelado;

  // ---------- Estado ----------
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();
  private List<Pedido> cache = List.of();

  private int limit = 20;
  private int offset = 0;
  private int total = 0;
  private String filtro = "";
  private String filtroEstado = "";

  // mantener selección entre refrescos
  private Integer idSeleccionado = null;

  // referencias a tareas para poder cancelarlas
  private Task<List<Pedido>> consultaTask;
  private Task<Void> patchTask;

  // ---------- Row para la tabla ----------
  public static class Row {
    private final String num, cliente, fecPed, fecEnt, horEnt, estado, total;
    public Row(String num, String cliente, String fecPed, String fecEnt, String horEnt, String estado, String total) {
      this.num=num; this.cliente=cliente; this.fecPed=fecPed; this.fecEnt=fecEnt; this.horEnt=horEnt; this.estado=estado; this.total=total;
    }
    public String getNum(){ return num; } public String getCliente(){ return cliente; }
    public String getFecPed(){ return fecPed; } public String getFecEnt(){ return fecEnt; }
    public String getHorEnt(){ return horEnt; } public String getEstado(){ return estado; }
    public String getTotal(){ return total; }
  }

  // ---------- Ciclo de vida ----------
  @FXML
  public void initialize() {
    cbEstado.setItems(FXCollections.observableArrayList(
        "", "REGISTRADO","COCINA","COCINANDO","COCINADO","ENTREGADO","CANCELADO"
    ));

    colNum.setCellValueFactory(new PropertyValueFactory<>("num"));
    colCliente.setCellValueFactory(new PropertyValueFactory<>("cliente"));
    colFecPed.setCellValueFactory(new PropertyValueFactory<>("fecPed"));
    colFecEnt.setCellValueFactory(new PropertyValueFactory<>("fecEnt"));
    colHorEnt.setCellValueFactory(new PropertyValueFactory<>("horEnt"));
    colEstado.setCellValueFactory(new PropertyValueFactory<>("estado"));
    colTotal.setCellValueFactory(new PropertyValueFactory<>("total"));

    // Enter = buscar
    txtBuscar.setOnAction(e -> buscar());

    // Selección estado
    cbEstado.setOnAction(e -> { filtroEstado = s(cbEstado.getValue()); offset=0; consultar(); });

    // Guardar selección por ÍTEM (robusto ante ordenamiento)
    tbl.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
      if (newRow != null) {
        try { idSeleccionado = Integer.parseInt(newRow.getNum()); }
        catch (NumberFormatException ex) { idSeleccionado = null; }
      }
    });

    refrescar();
  }

  // ---------- Acciones básicas ----------
  @FXML public void buscar(){ filtro = s(txtBuscar.getText()); offset=0; consultar(); }
  @FXML public void refrescar(){ offset=0; consultar(); }
  @FXML public void paginaAnterior(){ if (offset - limit >= 0) { offset -= limit; consultar(); } }
  @FXML public void paginaSiguiente(){ if (offset + limit < total) { offset += limit; consultar(); } }

  // ---------- Exportar CSV ----------
  @FXML public void exportarCSV() {
    if (tbl.getItems()==null || tbl.getItems().isEmpty()) { alert("No hay datos para exportar."); return; }
    var fc = new javafx.stage.FileChooser();
    fc.setTitle("Guardar pedidos (CSV)");
    fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV","*.csv"));
    fc.setInitialFileName("pedidos.csv");
    var f = fc.showSaveDialog(tbl.getScene().getWindow());
    if (f==null) return;
    try (var bw = new java.io.BufferedWriter(new java.io.FileWriter(f, false))) {
      bw.write("Nro,Cliente,FechaPedido,FechaEntrega,HoraEntrega,Estado,Total"); bw.newLine();
      for (Row r : tbl.getItems()) {
        bw.write(csv(r.getNum())+","+csv(r.getCliente())+","+csv(r.getFecPed())+","+csv(r.getFecEnt())+","+csv(r.getHorEnt())+","+csv(r.getEstado())+","+csv(r.getTotal()));
        bw.newLine();
      }
      info("Exportado: " + f.getAbsolutePath());
    } catch(Exception ex){ alert("Error al exportar: "+ex.getMessage()); }
  }
@FXML public void nuevoPedido() {
  try {
    var url = getClass().getResource("/com/mycompany/pasteleria/desktop/view/NuevoPedido.fxml");
    if (url == null) throw new IllegalStateException("Falta NuevoPedido.fxml");
    Dialog<ButtonType> dlg = new Dialog<>();
    dlg.setTitle("Nuevo Pedido");
    dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
    javafx.scene.Parent root = javafx.fxml.FXMLLoader.load(url);
    dlg.getDialogPane().setContent(root);

    // Obtener el controller para pedir el payload al aceptar
    javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(url);
    javafx.scene.Parent root2 = loader.load();
    NuevoPedidoController ctrl = loader.getController();

    dlg.getDialogPane().setContent(root2);
    var res = dlg.showAndWait();
    if (res.isPresent() && res.get() == ButtonType.OK) {
      // Enviar a Supabase
      var payload = ctrl.buildPayload();       // JSON pedido
      var detalles = ctrl.buildDetallesArray(); // JSON array de detalles
      crearPedidoEnBD(payload, detalles);
    }
  } catch (Exception e) {
    alert("No se pudo abrir el diálogo.\n" + e.getMessage());
  }
}

// Inserta pedido y luego sus detalles
private void crearPedidoEnBD(String pedidoJson, String detallesJson) {
  setLoading(true);
  Task<Void> task = new Task<>() {
    @Override protected Void call() throws Exception {
      try {
        // 1) Crear pedido (REGISTRADO). Prefer: return=representation devuelve el id
        var respPedido = api.postJson("/pedidos", pedidoJson);
        if (respPedido.statusCode() < 200 || respPedido.statusCode() >= 300) {
          throw new RuntimeException("HTTP " + respPedido.statusCode() + "\n" + respPedido.body());
        }
        // Parsear id_pedido
        java.util.List<com.mycompany.pasteleria.desktop.model.Pedido> creados =
            new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                respPedido.body(),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<com.mycompany.pasteleria.desktop.model.Pedido>>(){}
            );
        if (creados.isEmpty() || creados.get(0).id_pedido == null)
          throw new RuntimeException("No se recibió id_pedido.");

        int idPedido = creados.get(0).id_pedido;

        // 2) Insertar detalles (array) asignando id_pedido
        //   Reemplazamos el marcador __ID_PEDIDO__ del JSON
        String detallesPayload = detallesJson.replace("__ID_PEDIDO__", String.valueOf(idPedido));
        var respDet = api.postJson("/detalle_pedido", detallesPayload);
        if (respDet.statusCode() < 200 || respDet.statusCode() >= 300) {
          throw new RuntimeException("HTTP " + respDet.statusCode() + "\n" + respDet.body());
        }
        return null;
      } catch (Exception ex) {
        throw ex;
      }
    }
  };
  task.setOnSucceeded(e -> { info("Pedido creado."); consultar(); });
  task.setOnFailed(e -> { setLoading(false); showError("No se pudo crear el pedido.\n" +
      (task.getException()!=null? task.getException().getMessage() : "")); });
  new Thread(task,"create-pedido").start();
}
  // ---------- Cargar desde Supabase ----------
  private void consultar() {
    cancelar(consultaTask);
    setLoading(true);

    // SELECT con relación embebida a cliente
    String select = enc("id_pedido,id_cliente,fecha_pedido,fecha_entrega,hora_entrega,estado,total,cliente:cliente(nombre,apellido)");
    StringBuilder path = new StringBuilder("/pedidos?select=").append(select);

    // orden
    path.append("&order=fecha_pedido.desc");

    // estado (servidor)
    if (!filtroEstado.isBlank()) path.append("&estado=eq.").append(enc(filtroEstado));

    // paginación
    path.append("&limit=").append(limit).append("&offset=").append(offset);

    // búsqueda: por número de pedido (servidor) + por nombre cliente (cliente)
    String term = filtro.replace("%","").trim();
    boolean termEsNumero = !term.isBlank() && term.chars().allMatch(Character::isDigit);
    if (termEsNumero) {
      path.append("&id_pedido=eq.").append(term); // no encodes needed (solo dígitos)
    }

    consultaTask = new Task<>() {
      @Override protected List<Pedido> call() throws Exception {
        var resp = api.getRespWithCount(path.toString());
        int code = resp.statusCode();
        String body = resp.body();
        if (code < 200 || code >= 300) {
          throw new RuntimeException("HTTP " + code + "\nPath: " + path + "\nBody:\n" + body);
        }

        // total
        String cr = resp.headers().firstValue("Content-Range").orElse("");
        total = parseTotal(cr).orElse(0);

        var pedidos = om.readValue(body, new TypeReference<List<Pedido>>(){});
        cache = pedidos;

        // filtro por nombre en memoria (si el término NO es numérico)
        if (!term.isBlank() && !termEsNumero) {
          String t = term.toLowerCase();
          pedidos = pedidos.stream().filter(p -> {
            String nombre = (p.cliente!=null? s(p.cliente.nombre) : "");
            String ape = (p.cliente!=null? s(p.cliente.apellido): "");
            String full = (nombre + " " + ape).toLowerCase().trim();
            return full.contains(t);
          }).collect(Collectors.toList());
        }
        return pedidos;
      }
    };

    consultaTask.setOnSucceeded(ev -> {
      List<Pedido> pedidos = consultaTask.getValue();
      pintar(pedidos);
      actualizarKpis();
      // re-seleccionar si es posible
      if (idSeleccionado != null) seleccionarPorId(idSeleccionado);
      setLoading(false);
    });

    consultaTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(consultaTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo cargar pedidos.\n" + ex.getMessage());
    });

    new Thread(consultaTask,"supabase-pedidos").start();
  }

  private void pintar(List<Pedido> pedidos) {
    var rows = pedidos.stream().map(p -> {
      String cliente = (p.cliente!=null)? ((s(p.cliente.nombre)+" "+s(p.cliente.apellido)).trim()) : "—";
      return new Row(
          String.valueOf(p.id_pedido),
          cliente.isBlank()? "—" : cliente,
          fechaBonita(s(p.fecha_pedido)),   // ISO -> "yyyy-MM-dd HH:mm"
          s(p.fecha_entrega),
          s(p.hora_entrega),
          s(p.estado),
          s(p.total)
      );
    }).collect(Collectors.toList());

    tbl.setItems(FXCollections.observableArrayList(rows));

    int from = total==0? 0 : offset+1;
    int to = Math.min(offset+limit, Math.max(total, offset+rows.size()));
    lblRango.setText("Mostrando " + from + "–" + to + " de " + total);
  }

  private void actualizarKpis() {
    lblTotal.setText(String.valueOf(total));
    int pagAct = (total==0)? 0 : (offset/limit)+1;
    int pags   = (total==0)? 0 : ((total-1)/limit)+1;
    lblPagina.setText(pagAct + " / " + pags);
    lblFiltro.setText(filtro.isBlank()? "—" : filtro);
    lblEstado.setText(filtroEstado.isBlank()? "—" : filtroEstado);
  }

  private void seleccionarPorId(Integer id) {
    if (id == null) return;
    for (int i = 0; i < tbl.getItems().size(); i++) {
      Row r = tbl.getItems().get(i);
      if (String.valueOf(id).equals(r.getNum())) {
        tbl.getSelectionModel().select(i);
        tbl.scrollTo(i);
        break;
      }
    }
  }

  private String fechaBonita(String isoDateTime) {
    if (isoDateTime == null || isoDateTime.isBlank()) return "—";
    String s = isoDateTime.replace('T',' ');
    // 2025-10-23 15:30:00 -> 16 chars "yyyy-MM-dd HH:mm"
    int len = Math.min(16, s.length());
    return s.substring(0, len);
  }

  // ---------- Cambios de estado (PATCH) ----------
  @FXML public void toCocina(){ cambiarEstado("COCINA"); }
  @FXML public void toCocinando(){ cambiarEstado("COCINANDO"); }
  @FXML public void toCocinado(){ cambiarEstado("COCINADO"); }
  @FXML public void toEntregado(){ cambiarEstado("ENTREGADO"); }
  @FXML public void toCancelado(){ cambiarEstado("CANCELADO"); }

  private Optional<Integer> pedidoSeleccionado() {
    Row row = tbl.getSelectionModel().getSelectedItem();
    if (row == null) return Optional.empty();
    try { return Optional.of(Integer.parseInt(row.getNum())); }
    catch(Exception e){ return Optional.empty(); }
  }

  private void cambiarEstado(String nuevo) {
    var maybeId = pedidoSeleccionado();
    if (maybeId.isEmpty()) { alert("Selecciona un pedido."); return; }

    cancelar(patchTask);
    setLoading(true);

    patchTask = new Task<>() {
      @Override protected Void call() throws Exception {
        String body = "{\"estado\":\""+nuevo+"\"}";
        var resp = api.patchJson("/pedidos?id_pedido=eq." + maybeId.get(), body);
        int code = resp.statusCode();
        if (code == 200 || code == 204) return null;
        throw new RuntimeException("Error HTTP " + code + "\n" + resp.body());
      }
    };

    patchTask.setOnSucceeded(ev -> {
      info("Estado actualizado a " + nuevo);
      idSeleccionado = maybeId.get(); // mantener selección
      consultar();
    });

    patchTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(patchTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo actualizar.\n" + ex.getMessage());
    });

    new Thread(patchTask,"supabase-pedidos-patch").start();
  }

  // ---------- Helpers ----------
  private void setLoading(boolean v){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> setLoading(v)); return; }
    if (loader != null) loader.setVisible(v);
    if (tbl != null) tbl.setDisable(v);

    if (txtBuscar    != null) txtBuscar.setDisable(v);
    if (cbEstado     != null) cbEstado.setDisable(v);
    if (btnBuscar    != null) btnBuscar.setDisable(v);
    if (btnRefrescar != null) btnRefrescar.setDisable(v);
    if (btnPrev      != null) btnPrev.setDisable(v);
    if (btnNext      != null) btnNext.setDisable(v);
    if (btnExport    != null) btnExport.setDisable(v);

    if (btnToCocina     != null) btnToCocina.setDisable(v);
    if (btnToCocinando  != null) btnToCocinando.setDisable(v);
    if (btnToCocinado   != null) btnToCocinado.setDisable(v);
    if (btnToEntregado  != null) btnToEntregado.setDisable(v);
    if (btnToCancelado  != null) btnToCancelado.setDisable(v);
  }

  private static String s(String x){ return x==null? "" : x; }
  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }

  private static Optional<Integer> parseTotal(String contentRange) {
    try {
      if (contentRange==null || !contentRange.contains("/")) return Optional.empty();
      String after = contentRange.substring(contentRange.indexOf('/')+1).trim();
      return Optional.of(Integer.parseInt(after));
    } catch(Exception e){ return Optional.empty(); }
  }

  private static String csv(String s){
    String v = (s==null)?"":s;
    if (v.contains(",")||v.contains("\"")||v.contains("\n")) v = "\"" + v.replace("\"","\"\"") + "\"";
    return v;
  }

  private void alert(String m){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> alert(m)); return; }
    new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait();
  }
  private void info(String m){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> info(m)); return; }
    new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait();
  }
  private void showError(String m){ alert(m); }

  private static void cancelar(Task<?> t) {
    if (t != null && t.isRunning()) t.cancel(true);
  }
}
