// src/main/java/com/mycompany/pasteleria/desktop/controller/PagosController.java
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
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class PagosController {

  // -------- UI --------
  @FXML private TextField txtBuscar;
  @FXML private TableView<Row> tbl;
  @FXML private TableColumn<Row, String> colIdPedido, colMonto, colMetodo, colEstado, colSubido;

  @FXML private Label lblTotal, lblPagina, lblFiltro, lblRango;

  @FXML private WebView webComprobante;
  @FXML private Label lblUrl, lblTotalPedido, lblMontoComprobante, lblDiferencia;

  @FXML private Button btnBuscar, btnRefrescar, btnPrev, btnNext;
  @FXML private Button btnAprobar, btnFaltoPago, btnCancelar;

  @FXML private ProgressIndicator loader;
  @FXML private Region shade;

  // -------- Estado --------
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  private int limit = 20;
  private int offset = 0;
  private int total  = 0;

  private String filtro = ""; // por id_pedido (numérico) o método (texto)
  private Task<List<Map<String,Object>>> consultaTask;
  private Task<Void> patchTask;

  private List<Map<String,Object>> ultimaRespuesta = List.of();

  // -------- Fila de tabla --------
  public static class Row {
    // Guardamos id_pago internamente para poder patch a pagos.monto, aunque no se muestre.
    private final String idPago, idPedido, monto, metodo, estadoPedido, subido, url, totalPedido;
    public Row(String idPago, String idPedido, String monto,
               String metodo, String estadoPedido, String subido,
               String url, String totalPedido) {
      this.idPago=idPago; this.idPedido=idPedido; this.monto=monto; this.metodo=metodo;
      this.estadoPedido=estadoPedido; this.subido=subido; this.url=url; this.totalPedido=totalPedido;
    }
    public String getIdPago(){ return idPago; }
    public String getIdPedido(){ return idPedido; }
    public String getMonto(){ return monto; }
    public String getMetodo(){ return metodo; }
    public String getEstado(){ return estadoPedido; }
    public String getSubido(){ return subido; }
    public String getUrl(){ return url; }
    public String getTotalPedido(){ return totalPedido; }
  }

  @FXML
  public void initialize() {
    // columnas
    colIdPedido.setCellValueFactory(new PropertyValueFactory<>("idPedido"));
    colMetodo.setCellValueFactory(new PropertyValueFactory<>("metodo"));
    colEstado.setCellValueFactory(new PropertyValueFactory<>("estado"));
    colSubido.setCellValueFactory(new PropertyValueFactory<>("subido"));
    colMonto.setCellValueFactory(new PropertyValueFactory<>("monto"));

    // Monto editable (texto → validado numérico S/ XX.XX)
    colMonto.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<>() {
      @Override public String toString(String s) { return s; }
      @Override public String fromString(String s) { return s==null? "" : s.trim(); }
    }));
colMonto.setOnEditCommit(ev -> {
  final Row oldRow = ev.getRowValue();
  final String newText = ev.getNewValue() == null ? "" : ev.getNewValue().trim();
  final BigDecimal nuevoMonto = parseMoney(newText);

  if (nuevoMonto == null) {
    info("Monto inválido. Usa formato 123.45");
    tbl.refresh();
    return;
  }

  // Formateo bonito para mostrar en la tabla
  final String pretty = money(nuevoMonto);

  // Construimos la nueva fila inmutable
  final Row newRow = new Row(
      oldRow.getIdPago(),
      oldRow.getIdPedido(),
      pretty,
      oldRow.getMetodo(),
      oldRow.getEstado(),
      oldRow.getSubido(),
      oldRow.getUrl(),
      oldRow.getTotalPedido()
  );

  // Índice y claves que vamos a necesitar en lambdas internas
  final int rowIndex = ev.getTablePosition().getRow();
  final String idPagoLocal = newRow.getIdPago();
  final String idPedidoLocal = newRow.getIdPedido();

  // Pintamos de inmediato en UI
  tbl.getItems().set(rowIndex, newRow);
  tbl.getSelectionModel().select(rowIndex);

  // Si no hay id_pago, no podemos PATCHear el monto
  if (idPagoLocal == null || idPagoLocal.isBlank()) {
    info("No se pudo guardar el monto (id_pago vacío).");
    return;
  }

  cancelar(patchTask);
  setLoading(true);

  patchTask = new Task<>() {
    @Override protected Void call() throws Exception {
      // En BD 'monto' es numérico
      String body = "{\"monto\": " + nuevoMonto.setScale(2, RoundingMode.HALF_UP).toPlainString() + "}";
      var resp = api.patchJson("/pagos?id_pago=eq." + idPagoLocal, body);
      int code = resp.statusCode();
      if (code == 200 || code == 204) return null;
      throw new RuntimeException("Error HTTP " + code + "\n" + resp.body());
    }
  };

  patchTask.setOnSucceeded(e2 -> {
    setLoading(false);

    // Comparar contra total del pedido para aprobar automático o sugerir FALTO_PAGO
    final BigDecimal totalPedido = parseMoney(newRow.getTotalPedido());
    if (totalPedido != null && nuevoMonto.compareTo(totalPedido) == 0) {
      // Auto-aprobar pedido
      aprobarAuto(idPedidoLocal);
    } else if (totalPedido != null) {
      final BigDecimal diff = totalPedido.subtract(nuevoMonto).setScale(2, RoundingMode.HALF_UP);
      final boolean falta = diff.signum() > 0;

      Alert a = new Alert(Alert.AlertType.CONFIRMATION);
      a.setHeaderText(null);
      a.setContentText(
          "El comprobante no coincide con el total del pedido.\n" +
          "Total: " + money(totalPedido) + "\n" +
          "Comprobante: " + money(nuevoMonto) + "\n" +
          (falta ? ("Saldo pendiente: " + money(diff)) : ("Exceso: " + money(diff.abs()))) +
          "\n\n¿Marcar el pedido como FALTO_PAGO?"
      );
      a.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
      a.showAndWait().ifPresent(bt -> {
        if (bt == ButtonType.OK) cambiarEstadoPedido(idPedidoLocal, "FALTO_PAGO");
      });
    }

    // Re-seleccionar fila por si la tabla se refrescó
    seleccionarPorId(idPagoLocal);
  });

  patchTask.setOnFailed(e2 -> {
    setLoading(false);
    Throwable ex = Optional.ofNullable(patchTask.getException()).orElse(new RuntimeException("Error desconocido"));
    showError("No se pudo guardar el monto.\n" + ex.getMessage());
    consultar(); // recarga desde servidor para mantener consistencia
  });

  new Thread(patchTask, "pagos-monto-patch").start();
});


    // formato monto y estado
    colMonto.setStyle("-fx-alignment: CENTER-RIGHT;");
    colEstado.setCellFactory(col -> new TableCell<>() {
      @Override protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) { setGraphic(null); setText(null); return; }
        Label badge = new Label(item);
        badge.getStyleClass().add("pill");
        switch (item.toUpperCase(Locale.ROOT)) {
          case "APROBADO"   -> badge.setStyle("-fx-background-color:#c6f6d5; -fx-text-fill:#1f6b43;");
          case "FALTO_PAGO" -> badge.setStyle("-fx-background-color:#ffe3c1; -fx-text-fill:#7a3f00;");
          case "CANCELADO"  -> badge.setStyle("-fx-background-color:#ffccd5; -fx-text-fill:#8a1c2b;");
          default           -> badge.setStyle("-fx-background-color:#e9ecef; -fx-text-fill:#333;");
        }
        setGraphic(badge);
        setText(null);
        setAlignment(Pos.CENTER);
      }
    });

    // seleccionar → detalle
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

    // búsqueda
    if (txtBuscar != null) txtBuscar.setOnAction(e -> buscar());

    // carga inicial
    refrescar();

    tbl.setEditable(true);
colMonto.setEditable(true);
  }

  // -------- Acciones --------
  @FXML public void buscar()         { filtro = s(txtBuscar.getText()); offset=0; consultar(); }
  @FXML public void refrescar()      { offset=0; consultar(); }
  @FXML public void paginaAnterior() { if (offset - limit >= 0) { offset -= limit; consultar(); } }
  @FXML public void paginaSiguiente(){ if (offset + limit < total) { offset += limit; consultar(); } }
  @FXML public void abrirEnNavegador(){ abrirComprobante(); }

  @FXML
  public void abrirComprobante() {
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

  // Botones (modifican pedidos.estado)
  @FXML public void aprobar()   { accionEstadoDesdeSeleccion("APROBADO"); }
  @FXML public void faltoPago() { accionEstadoDesdeSeleccion("FALTO_PAGO"); }
  @FXML public void cancelar()  { accionEstadoDesdeSeleccion("CANCELADO"); }

  private void accionEstadoDesdeSeleccion(String nuevo) {
    Row r = tbl.getSelectionModel().getSelectedItem();
    if (r == null) { alert("Selecciona un pago."); return; }
    cambiarEstadoPedido(r.getIdPedido(), nuevo);
  }

  private void aprobarAuto(String idPedido) {
    cambiarEstadoPedido(idPedido, "APROBADO");
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
      consultar(); // como el filtro es EN_REVISION, si apruebas desaparecerá de la lista
    });
    patchTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(patchTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo actualizar el estado del pedido.\n" + ex.getMessage());
    });
    new Thread(patchTask,"pedidos-estado-patch").start();
  }

  // -------- Consulta a Supabase --------
  private void consultar() {
    cancelar(consultaTask);
    setLoading(true);

    // Sólo EN_REVISION (en tabla pedidos). Traemos:
    // pagos: id_pago, id_pedido, metodo, comprobante_url, fecha_registro, monto
    // pedidos: total, estado
    String select =
        "id_pago,id_pedido,metodo,comprobante_url,fecha_registro,monto," +
        "pedido:pedidos(total,estado)";

    StringBuilder path = new StringBuilder("/pagos?select=").append(enc(select))
        .append("&order=").append(enc("fecha_registro.desc"))
        .append("&limit=").append(limit)
        .append("&offset=").append(offset)
        // Filtro del embebido: sólo pedidos EN_REVISION
        .append("&pedido.estado=eq.").append(enc("EN_REVISION"));

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
      actualizarKpis();
      setLoading(false);
    });
    consultaTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(consultaTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo cargar pagos.\n" + ex.getMessage());
    });

    new Thread(consultaTask,"pagos-en-revision").start();
  }

  private void pintar(List<Map<String,Object>> pagos) {
    var rows = pagos.stream().map(m -> {
      String idPago    = String.valueOf(m.getOrDefault("id_pago",""));
      String idPedido  = String.valueOf(m.getOrDefault("id_pedido",""));
      String metodo    = s(String.valueOf(m.getOrDefault("metodo","")));
      String url       = s(String.valueOf(m.getOrDefault("comprobante_url","")));
      String fechaIso  = s(String.valueOf(m.getOrDefault("fecha_registro","")));
      String subido    = fechaBonita(fechaIso);

      BigDecimal montoBD = getBigDecimal(m.get("monto"));
      String montoTxt = (montoBD == null) ? "S/ —" : money(montoBD);

      String estadoPedido = "—";
      String totalPedidoTxt = "S/ —";
      Object pedidoObj = m.get("pedido");
      if (pedidoObj instanceof Map<?,?> p) {
        BigDecimal tot = getBigDecimal(p.get("total"));
        if (tot != null) totalPedidoTxt = money(tot);
        Object est = p.get("estado");
        if (est != null) estadoPedido = String.valueOf(est);
      }

      return new Row(idPago, idPedido, montoTxt, metodo, estadoPedido, subido, url, totalPedidoTxt);
    }).collect(Collectors.toList());

    tbl.setItems(FXCollections.observableArrayList(rows));

    int from = total==0? 0 : offset+1;
    int to = Math.min(offset+limit, Math.max(total, offset+rows.size()));
    if (lblRango != null) lblRango.setText("Mostrando " + from + "–" + to + " de " + total);

    pintarDetalle(null);
    actualizarBotones();
  }

  private void seleccionarPorId(String idPago) {
    if (idPago == null) return;
    for (int i=0; i<tbl.getItems().size(); i++) {
      if (idPago.equals(tbl.getItems().get(i).getIdPago())) {
        tbl.getSelectionModel().select(i);
        tbl.scrollTo(i);
        break;
      }
    }
  }

  private void pintarDetalle(Map<String,Object> pago) {
    String url = "";
    BigDecimal monto = null;
    BigDecimal totalPedido = null;

    if (pago != null) {
      url = s(String.valueOf(pago.get("comprobante_url")));
      monto = getBigDecimal(pago.get("monto"));
      Object po = pago.get("pedido");
      if (po instanceof Map<?,?> p) totalPedido = getBigDecimal(p.get("total"));
    }

    WebEngine eng = (webComprobante!=null)? webComprobante.getEngine() : null;
    if (eng != null) {
      if (url.isBlank())
        eng.loadContent("<html><body style='font-family:sans-serif;color:#666;padding:16px'>Sin comprobante</body></html>");
      else eng.load(url);
    }

    if (lblUrl != null) lblUrl.setText(url.isBlank()? "—" : url);
    if (lblTotalPedido != null)      lblTotalPedido.setText(totalPedido==null? "S/ —" : money(totalPedido));
    if (lblMontoComprobante != null) lblMontoComprobante.setText(monto==null? "S/ —" : money(monto));

    if (lblDiferencia != null) {
      if (monto==null || totalPedido==null) { lblDiferencia.setText("—"); return; }
      BigDecimal diff = monto.subtract(totalPedido).setScale(2, RoundingMode.HALF_UP);
      String sign = diff.signum() >= 0 ? "+" : "-";
      lblDiferencia.setText(sign + diff.abs().toPlainString());
    }
  }

  private void actualizarBotones() {
    boolean has = tbl.getSelectionModel().getSelectedItem() != null;
    if (btnAprobar!=null)   btnAprobar.setDisable(!has);
    if (btnFaltoPago!=null) btnFaltoPago.setDisable(!has);
    if (btnCancelar!=null)  btnCancelar.setDisable(!has);
  }

  private void actualizarKpis() {
    if (lblTotal != null)  lblTotal.setText(String.valueOf(total));
    int pagAct = (total==0)? 0 : (offset/limit)+1;
    int pags   = (total==0)? 0 : ((total-1)/limit)+1;
    if (lblPagina != null)  lblPagina.setText(pagAct + " / " + pags);
    if (lblFiltro != null)  lblFiltro.setText(filtro.isBlank()? "—" : filtro);
  }

  // -------- Helpers --------
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
    if (btnFaltoPago != null) btnFaltoPago.setDisable(dis);
    if (btnCancelar  != null) btnCancelar.setDisable(dis);
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
