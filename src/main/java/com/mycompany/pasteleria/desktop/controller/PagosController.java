// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/PagosController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.awt.Desktop;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/* ============================================================
 * CONTROLADOR: Aprobar Pagos
 * QUÉ (resumen):
 *  - La TABLA muestra: N° Pedido | Total(pedidos.total) | Método | Estado(pedidos.estado) | Fecha
 *  - El panel derecho tiene "Monto comprobante" (TextField) para guardar en pagos.monto
 *  - Filtrado por pedidos.estado (por defecto EN_REVISION). Ignora pagos.estado_pago
 *  - Tras aprobar o marcar no válido, la fila desaparece (INNER JOIN al filtrar)
 * POR QUÉ:
 *  - Cumple UX pedida: datos reales del pedido y edición del monto de pago.
 * ============================================================ */
public class PagosController {

  // ---------- UI ----------
  @FXML private TextField txtBuscar;
  @FXML private ComboBox<String> cmbEstado;

  @FXML private TableView<Row> tbl;
  @FXML private TableColumn<Row, String> colIdPedido, colMonto, colMetodo, colEstado, colSubido;

  @FXML private Label lblRango;

  @FXML private WebView webComprobante;
  @FXML private Label lblUrl, lblTotalPedido, lblDiferencia;
  @FXML private TextField txtMontoComprobante;

  @FXML private Button btnBuscar, btnRefrescar, btnPrev, btnNext;
  @FXML private Button btnAprobar, btnPagoNoValido;

  @FXML private ProgressIndicator loader;
  @FXML private Region shade;

  // ---------- Estado ----------
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  private int limit = 20;
  private int offset = 0;
  private int total  = 0;

  private String filtro = "";                         // texto del buscador (id_pedido o método)
  private String estadoSeleccionado = "EN_REVISION";  // filtro por defecto

  private Task<List<Map<String,Object>>> consultaTask;
  private Task<Void> patchTask;

  private List<Map<String,Object>> ultimaRespuesta = List.of();

  // ---------- Fila de tabla (usa TOTAL de pedido, no pagos.monto) ----------
  public static class Row {
    private final String idPago, idPedido, totalPedido, metodo, estadoPedido, subido, url;
    public Row(String idPago, String idPedido, String totalPedido,
               String metodo, String estadoPedido, String subido, String url) {
      this.idPago=idPago; this.idPedido=idPedido; this.totalPedido=totalPedido;
      this.metodo=metodo; this.estadoPedido=estadoPedido; this.subido=subido; this.url=url;
    }
    public String getIdPago(){ return idPago; }
    public String getIdPedido(){ return idPedido; }
    public String getMonto(){ return totalPedido; } // la columna "Total" usa esto
    public String getMetodo(){ return metodo; }
    public String getEstado(){ return estadoPedido; }
    public String getSubido(){ return subido; }
    public String getUrl(){ return url; }
  }

  // ============================================================
  // INIT (configura columnas, estados, listeners y carga inicial)
  // ============================================================
  @FXML
  public void initialize() {
    // Cargar CSS global desde el controlador (ruta que indicaste)
    Platform.runLater(() -> {
      try {
        var url = getClass().getResource("/com/mycompany/pasteleria/desktop/app.css");
        if (url != null && tbl != null && tbl.getScene() != null) {
          var css = url.toExternalForm();
          if (!tbl.getScene().getStylesheets().contains(css)) {
            tbl.getScene().getStylesheets().add(css);
          }
        }
      } catch (Exception ignored) { }
    });

    // Columnas → propiedades de Row
    colIdPedido.setCellValueFactory(new PropertyValueFactory<>("idPedido"));
    colMetodo.setCellValueFactory(new PropertyValueFactory<>("metodo"));
    colEstado.setCellValueFactory(new PropertyValueFactory<>("estado"));
    colSubido.setCellValueFactory(new PropertyValueFactory<>("subido"));
    colMonto.setCellValueFactory(new PropertyValueFactory<>("monto")); // ahora es pedidos.total mostrado

    // Alineación visual y "badges" por estado (usa CSS .badge y variantes)
    colMonto.setStyle("-fx-alignment: CENTER-RIGHT;");
    colEstado.setCellFactory(col -> new TableCell<>() {
      @Override protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) { setGraphic(null); setText(null); return; }
        Label badge = new Label(item);
        badge.getStyleClass().add("badge");
        String key = item.toUpperCase(Locale.ROOT);
        if (key.equals("APROBADO"))            badge.getStyleClass().add("badge--verde");
        else if (key.equals("PAGO_NO_VALIDO")) badge.getStyleClass().add("badge--rojo");
        else if (key.equals("CANCELADO"))      badge.getStyleClass().add("badge--rojo");
        else if (key.equals("EN_REVISION"))    badge.getStyleClass().add("badge--azul");
        else if (key.contains("COCINA") || key.contains("PREPARACION")) badge.getStyleClass().add("badge--naranja");
        else if (key.contains("ENVIADO") || key.contains("RUTA"))       badge.getStyleClass().add("badge--celeste");
        else if (key.contains("ENTREGADO") || key.contains("LISTO"))    badge.getStyleClass().add("badge--verde");
        else if (key.contains("ASIGNADO"))                               badge.getStyleClass().add("badge--morado");
        else                                                             badge.getStyleClass().add("badge--gris");
        setGraphic(badge);
        setText(null);
        setAlignment(Pos.CENTER);
      }
    });

    // Forzar exactamente 5 columnas (evita columnas “fantasma”)
    tbl.getColumns().setAll(colIdPedido, colMonto, colMetodo, colEstado, colSubido);

    // Selección de fila → pintar detalle y habilitar botones
    tbl.getSelectionModel().selectedItemProperty().addListener((o,oldV,newV) -> {
      Map<String,Object> pagoMap = null;
      if (newV != null) {
        for (var m : ultimaRespuesta) {
          if (String.valueOf(m.get("id_pedido")).equals(newV.getIdPedido())) { pagoMap = m; break; }
        }
      }
      pintarDetalle(pagoMap);
      actualizarBotones();
    });
      // 1) Política compatible con todas las versiones
    tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

    // 2) Reparto de ancho proporcional (evita filler/columna fantasma)
    Platform.runLater(() -> {
      double[] pesos = {1.0, 0.8, 1.0, 1.0, 1.2}; // Id, Total, Método, Estado, Fecha
      var cols = List.of(colIdPedido, colMonto, colMetodo, colEstado, colSubido);
      tbl.widthProperty().addListener((obs, oldW, newW) -> {
        double w = newW.doubleValue() - 20; // margen scrollbar/bordes
        double suma = 0;
        for (double p : pesos) suma += p;
        for (int i = 0; i < cols.size(); i++) {
          cols.get(i).setPrefWidth(Math.max(80, (pesos[i]/suma) * w));
        }
      });
    });

    // Combo de estados (incluye "TODOS")
    if (cmbEstado != null) {
      List<String> estados = List.of(
          "EN_REVISION", "APROBADO", "PAGO_NO_VALIDO",
          "EN_COCINA", "EN_PREPARACION", "SERVIDO",
          "ASIGNADO", "ENVIADO", "ENTREGADO", "NO_ENCONTRADO",
          "TODOS"
      );
      cmbEstado.setItems(FXCollections.observableArrayList(estados));
      cmbEstado.getSelectionModel().select("EN_REVISION");
      cmbEstado.setOnAction(e -> {
        String sel = cmbEstado.getSelectionModel().getSelectedItem();
        estadoSeleccionado = (sel == null || sel.isBlank()) ? "EN_REVISION" : sel;
        offset = 0;
        consultar();
      });
    }

    // Enter en buscador
    if (txtBuscar != null) txtBuscar.setOnAction(e -> buscar());

    // Carga inicial
    refrescar();
  }

  // ============================================================
  // ACCIONES BÁSICAS (buscar, refrescar, paginación, abrir URL)
  // ============================================================
  @FXML public void buscar()         { filtro = s(txtBuscar.getText()); offset=0; consultar(); }
  @FXML public void refrescar()      { offset=0; consultar(); }
  @FXML public void paginaAnterior() { if (offset - limit >= 0) { offset -= limit; consultar(); } }
  @FXML public void paginaSiguiente(){ if (offset + limit < total) { offset += limit; consultar(); } }

  @FXML
  public void abrirEnNavegador() {
    Row r = tbl.getSelectionModel().getSelectedItem();
    if (r == null) { alert("Selecciona un pago."); return; }
    String url = s(r.getUrl());
    if (url.isBlank()) { alert("Este pago no tiene URL de comprobante."); return; }
    try {
      if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(url));
      else alert("No se puede abrir el navegador en este sistema.");
    } catch (Exception e) {
      alert("No se pudo abrir el comprobante:\n" + e.getMessage());
    }
  }

  // ============================================================
  // GUARDAR MONTO COMPROBANTE (pagos.monto)
  // ============================================================
  @FXML
  public void guardarMontoComprobante() {
    Row r = tbl.getSelectionModel().getSelectedItem();
    if (r == null) { alert("Selecciona un pago."); return; }
    String idPago = r.getIdPago();
    if (idPago == null || idPago.isBlank()) { alert("id_pago vacío."); return; }

    BigDecimal nuevoMonto = parseMoney(txtMontoComprobante == null ? null : txtMontoComprobante.getText());
    if (nuevoMonto == null) { alert("Monto inválido. Usa formato 123.45"); return; }

    cancelar(patchTask);
    setLoading(true);

    patchTask = new Task<>() {
      @Override protected Void call() throws Exception {
        String body = "{\"monto\": " + nuevoMonto.setScale(2, RoundingMode.HALF_UP).toPlainString() + "}";
        var resp = api.patchJson("/pagos?id_pago=eq." + idPago, body);
        int code = resp.statusCode();
        if (code == 200 || code == 204) return null;
        throw new RuntimeException("Error HTTP " + code + "\n" + resp.body());
      }
    };
    patchTask.setOnSucceeded(e -> {
      setLoading(false);
      info("Monto guardado.");
      consultar(); // refresca comparativa/diferencia
    });
    patchTask.setOnFailed(e -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(patchTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo guardar el monto.\n" + ex.getMessage());
    });
    new Thread(patchTask, "pagos-guardar-monto").start();
  }

  // ============================================================
  // ACCIONES DE ESTADO (pedidos.estado) → 2 botones
  // ============================================================
  @FXML public void aprobar()       { accionEstadoDesdeSeleccion("APROBADO"); }
  @FXML public void pagoNoValido()  { accionEstadoDesdeSeleccion("PAGO_NO_VALIDO"); }

  private void accionEstadoDesdeSeleccion(String nuevo) {
    Row r = tbl.getSelectionModel().getSelectedItem();
    if (r == null) { alert("Selecciona un pago."); return; }
    cambiarEstadoPedido(r.getIdPedido(), nuevo);
  }

  private void cambiarEstadoPedido(String idPedido, String nuevoEstado) {
    if (idPedido == null || idPedido.isBlank()) { alert("Id de pedido vacío."); return; }
    cancelar(patchTask);
    setLoading(true);

    patchTask = new Task<>() {
      @Override protected Void call() throws Exception {
        String body = "{\"estado\":\"" + nuevoEstado + "\"}";
        var resp = api.patchJson("/pedidos?id_pedido=eq." + idPedido, body);
        int code = resp.statusCode();
        if (code == 200 || code == 204) return null;
        throw new RuntimeException("Error HTTP " + code + "\n" + resp.body());
      }
    };
    patchTask.setOnSucceeded(ev -> {
      info("Pedido " + idPedido + " → " + nuevoEstado);
      consultar(); // con INNER JOIN, al cambiar de estado sale de la lista
    });
    patchTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(patchTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo actualizar el estado del pedido.\n" + ex.getMessage());
    });
    new Thread(patchTask,"pedidos-estado-patch").start();
  }

  // ============================================================
  // CONSULTA (pagos + embed pedidos). INNER cuando hay filtro.
  // ============================================================
  private void consultar() {
    cancelar(consultaTask);
    setLoading(true);

    boolean usarInner = estadoSeleccionado != null
        && !estadoSeleccionado.isBlank()
        && !"TODOS".equalsIgnoreCase(estadoSeleccionado);

    String embed = usarInner
        ? "pedido:pedidos!inner(total,estado)"   // INNER JOIN si filtramos por estado
        : "pedido:pedidos(total,estado)";        // LEFT JOIN si “TODOS”

    String select =
        "id_pago,id_pedido,metodo,comprobante_url,fecha_registro,monto," + embed;

    StringBuilder path = new StringBuilder("/pagos?select=").append(enc(select))
        .append("&order=").append(enc("fecha_registro.desc"))
        .append("&limit=").append(limit)
        .append("&offset=").append(offset);

    if (usarInner) {
      path.append("&pedido.estado=eq.").append(enc(estadoSeleccionado));
    }

    // Buscador: id numérico → id_pedido; texto → método ilike
    String term = s(filtro).trim();
    if (!term.isBlank()) {
      if (term.chars().allMatch(Character::isDigit)) {
        path.append("&id_pedido=eq.").append(term);
      } else {
        path.append("&metodo=ilike.").append(enc("%"+term+"%"));
      }
    }

    consultaTask = new Task<>() {
      @Override protected List<Map<String, Object>> call() throws Exception {
        var resp = api.getRespWithCount(path.toString());
        int code = resp.statusCode();
        String body = resp.body();
        if (code < 200 || code >= 300) {
          throw new RuntimeException("HTTP " + code + "\nPath: " + path + "\nBody:\n" + body);
        }
        String cr = resp.headers().firstValue("Content-Range").orElse("");
        total = parseTotal(cr).orElse(0);
        return om.readValue(body, new TypeReference<List<Map<String,Object>>>(){});
      }
    };

    consultaTask.setOnSucceeded(ev -> {
      ultimaRespuesta = consultaTask.getValue();
      pintar(ultimaRespuesta);
      setLoading(false);
    });
    consultaTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(consultaTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo cargar pagos.\n" + ex.getMessage());
    });

    new Thread(consultaTask,"pagos-consulta").start();
  }

  // ============================================================
  // PINTAR TABLA + DETALLE
  // ============================================================
  private void pintar(List<Map<String,Object>> pagos) {
    var rows = pagos.stream().map(m -> {
      String idPago    = String.valueOf(m.getOrDefault("id_pago",""));
      String idPedido  = String.valueOf(m.getOrDefault("id_pedido",""));
      String metodo    = s(String.valueOf(m.getOrDefault("metodo","")));
      String url       = s(String.valueOf(m.getOrDefault("comprobante_url","")));
      String fechaIso  = s(String.valueOf(m.getOrDefault("fecha_registro","")));
      String subido    = fechaBonita(fechaIso);

      String totalPedidoTxt = "S/ —";
      String estadoPedido = "—";
      Object pedidoObj = m.get("pedido");
      if (pedidoObj instanceof Map<?,?> p) {
        BigDecimal tot = getBigDecimal(p.get("total"));
        if (tot != null) totalPedidoTxt = money(tot);
        Object est = p.get("estado");
        if (est != null) estadoPedido = String.valueOf(est);
      }

      return new Row(idPago, idPedido, totalPedidoTxt, metodo, estadoPedido, subido, url);
    }).collect(Collectors.toList());

    tbl.setItems(FXCollections.observableArrayList(rows));

    int from = total==0? 0 : offset+1;
    int to = Math.min(offset+limit, Math.max(total, offset+rows.size()));
    if (lblRango != null) lblRango.setText("Mostrando " + from + "–" + to + " de " + total);

    pintarDetalle(null);
    actualizarBotones();
  }

  private void pintarDetalle(Map<String,Object> pago) {
    String url = "";
    BigDecimal montoComprobante = null; // pagos.monto
    BigDecimal totalPedido = null;      // pedidos.total

    if (pago != null) {
      url = s(String.valueOf(pago.get("comprobante_url")));
      montoComprobante = getBigDecimal(pago.get("monto"));
      Object po = pago.get("pedido");
      if (po instanceof Map<?,?> p) totalPedido = getBigDecimal(p.get("total"));
    }

    WebEngine eng = (webComprobante!=null)? webComprobante.getEngine() : null;
    if (eng != null) {
      if (url.isBlank())
        eng.loadContent("<html><body style='font-family:sans-serif;color:#666;padding:16px'>Sin comprobante</body></html>");
      else eng.load(url);
    }

    if (lblUrl != null)          lblUrl.setText(url.isBlank()? "—" : url);
    if (lblTotalPedido != null)  lblTotalPedido.setText(totalPedido==null? "S/ —" : money(totalPedido));

    // Mostrar/editar monto comprobante actual
    if (txtMontoComprobante != null) {
      txtMontoComprobante.setText(
          montoComprobante==null? "" : montoComprobante.setScale(2, RoundingMode.HALF_UP).toPlainString()
      );
    }

    if (lblDiferencia != null) {
      if (montoComprobante==null || totalPedido==null) { lblDiferencia.setText("—"); return; }
      BigDecimal diff = montoComprobante.subtract(totalPedido).setScale(2, RoundingMode.HALF_UP);
      String sign = diff.signum() >= 0 ? "+" : "-";
      lblDiferencia.setText(sign + diff.abs().toPlainString());
    }
  }

  private void actualizarBotones() {
    boolean has = tbl.getSelectionModel().getSelectedItem() != null;
    if (btnAprobar!=null)       btnAprobar.setDisable(!has);
    if (btnPagoNoValido!=null)  btnPagoNoValido.setDisable(!has);
  }

  // ============================================================
  // HELPERS (UI, formato y parsing)
  // ============================================================
  private void setLoading(boolean v){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> setLoading(v)); return; }
    if (loader != null) loader.setVisible(v);
    if (shade  != null) shade.setVisible(v);
    if (tbl != null)    tbl.setDisable(v);

    boolean dis = v;
    if (txtBuscar    != null) txtBuscar.setDisable(dis);
    if (btnBuscar    != null) btnBuscar.setDisable(dis);
    if (btnRefrescar != null) btnRefrescar.setDisable(dis);
    if (btnPrev      != null) btnPrev.setDisable(dis);
    if (btnNext      != null) btnNext.setDisable(dis);
    if (btnAprobar   != null) btnAprobar.setDisable(dis);
    if (btnPagoNoValido != null) btnPagoNoValido.setDisable(dis);
    if (txtMontoComprobante != null) txtMontoComprobante.setDisable(dis);
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

  private static BigDecimal getBigDecimal(Object v) {
    try {
      if (v == null) return null;
      if (v instanceof Number n) return new BigDecimal(n.toString());
      String s = String.valueOf(v).replace(',','.');
      if (s.isBlank()) return null;
      return new BigDecimal(s);
    } catch(Exception e){ return null; }
  }

  private static BigDecimal parseMoney(String s) {
    if (s == null) return null;
    String t = s.replace("S/","").replace("$","").trim().replace(',','.');
    if (t.isBlank()) return null;
    try { return new BigDecimal(t); } catch (Exception e) { return null; }
  }

  private static String money(BigDecimal v) {
    if (v == null) return "S/ —";
    return "S/ " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String fechaBonita(String isoDateTime) {
    if (isoDateTime == null || isoDateTime.isBlank()) return "—";
    String s = isoDateTime.trim();
    int dot = s.indexOf('.');
    if (dot > 0) s = s.substring(0, dot);
    if (s.endsWith("Z")) s = s.substring(0, s.length()-1);
    s = s.replace('T', ' ');
    int len = Math.min(16, s.length());
    return s.substring(0, len);
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
