package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.DetallePedido;
import com.mycompany.pasteleria.desktop.model.Pedido;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * KDS (tarjetas) no rompe tu CocinaController (tablas).
 * Muestra pedidos en estados: COCINA, COCINANDO, opcionalmente COCINADO si filtras "TODOS".
 */
public class KDSController {

  // UI
  @FXML private ComboBox<String> cbFiltro;
  @FXML private CheckBox chkAutoRefresh;
  @FXML private Button btnRefrescar;
  @FXML private TilePane grid;
  @FXML private ProgressIndicator loader;

  // Estado
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();
  private volatile boolean destroyed = false;

  // AutoRefresh
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> autoTask;

  // Cache para minimizar parpadeos
  private Map<Integer, Node> cardById = new HashMap<>();

  @FXML
  public void initialize() {
    cbFiltro.getItems().setAll("PENDIENTE", "EN_PROCESO", "TODOS");
    cbFiltro.getSelectionModel().select("PENDIENTE");
    cbFiltro.setOnAction(e -> refrescar());

    chkAutoRefresh.setOnAction(e -> toggleAutoRefresh(chkAutoRefresh.isSelected()));
    btnRefrescar.setOnAction(e -> refrescar());

    // scheduler p/ autorefresh
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "kds-autorefresh");
      t.setDaemon(true);
      return t;
    });

    refrescar();
  }

  @FXML
  public void refrescar() {
    loadPedidosAsync();
  }

  private void toggleAutoRefresh(boolean on) {
    if (on) {
      if (autoTask != null && !autoTask.isCancelled()) autoTask.cancel(true);
      autoTask = scheduler.scheduleAtFixedRate(() -> Platform.runLater(this::refrescar),
          10, 10, TimeUnit.SECONDS); // cada 10s
    } else if (autoTask != null) {
      autoTask.cancel(true);
      autoTask = null;
    }
  }

  private void loadPedidosAsync() {
    setLoading(true);

    String whereEstados;
    String filtro = cbFiltro.getValue() == null ? "PENDIENTE" : cbFiltro.getValue();
    switch (filtro) {
      case "EN_PROCESO":
        whereEstados = enc("estado.eq.COCINANDO");
        break;
      case "TODOS":
        whereEstados = enc("estado.in.(COCINA,COCINANDO,COCINADO)");
        break;
      case "PENDIENTE":
      default:
        whereEstados = enc("estado.eq.COCINA");
    }

    String select = enc("id_pedido,fecha_pedido,fecha_entrega,hora_entrega,estado,total,cliente:cliente(nombre,apellido)");
    String path = "/pedidos?select=" + select
        + "&or=(" + whereEstados + ")"
        + "&order=fecha_pedido.asc"
        + "&limit=48";

    Task<List<Pedido>> task = new Task<>() {
      @Override protected List<Pedido> call() throws Exception {
        var resp = api.getResp(path);
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
          throw new RuntimeException("HTTP " + code + "\n" + resp.body());
        }
        return om.readValue(resp.body(), new TypeReference<List<Pedido>>(){});
      }
    };

    task.setOnSucceeded(e -> {
      List<Pedido> pedidos = task.getValue();
      renderCards(pedidos);
      setLoading(false);
    });
    task.setOnFailed(e -> {
      setLoading(false);
      showError("No se pudo cargar pedidos KDS.\n" +
          (task.getException() != null ? task.getException().getMessage() : ""));
    });

    new Thread(task, "kds-load").start();
  }

  private void renderCards(List<Pedido> pedidos) {
    // Mantener orden pero reusar nodos si ya existen por id
    Map<Integer, Node> next = new HashMap<>();
    grid.getChildren().clear();

    for (Pedido p : pedidos) {
      int id = Optional.ofNullable(p.id_pedido).orElse(-1);
      Node card = cardById.get(id);
      if (card == null) {
        card = buildCard(p);
      } else {
        updateCard(card, p);
      }
      next.put(id, card);
      grid.getChildren().add(card);
    }
    cardById = next;
  }

  private Node buildCard(Pedido p) {
    VBox card = new VBox(6);
    card.getStyleClass().add("card");
    card.setPadding(new Insets(12));

    // Encabezado: Pedido # + timer
    HBox head = new HBox(8);
    Label lblNro = new Label("Pedido #" + safe(p.id_pedido));
    lblNro.setStyle("-fx-font-weight:600;");

    Label lblTimer = new Label(elapsedFrom(p.fecha_pedido));
    lblTimer.getStyleClass().add("label-muted");
    HBox spacer = new HBox(); HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

    // Estado badge
    Label lblEstado = new Label(mapEstadoUi(p.estado));
    lblEstado.getStyleClass().addAll("badge", cssBadgeFor(p.estado));

    head.getChildren().addAll(lblNro, spacer, lblTimer, lblEstado);

    // Cuerpo: resumen de productos (corto)
    Label lblResumen = new Label(resumenProductos(p));
    lblResumen.setWrapText(true);

    // Notas (si las tuvieras en tu modelo, aquí es placeholder)
    Label lblNotas = new Label(notitas(p));
    lblNotas.getStyleClass().add("label-muted");
    lblNotas.setWrapText(true);

    // Botones
    HBox actions = new HBox(8);
    Button btnProceso = new Button("EN PROCESO");
    btnProceso.getStyleClass().add("btn-info");

    Button btnListo = new Button("LISTO");
    btnListo.getStyleClass().add("btn-success");

    actions.getChildren().addAll(btnProceso, btnListo);

    // Wiring de acciones
    btnProceso.setOnAction(e -> patchEstado(p.id_pedido, "COCINANDO"));
    btnListo.setOnAction(e -> patchEstado(p.id_pedido, "COCINADO"));

    // Guardar referencias en propiedades del nodo para updates
    card.getProperties().put("id", p.id_pedido);
    card.getProperties().put("lblTimer", lblTimer);
    card.getProperties().put("lblEstado", lblEstado);
    card.getProperties().put("lblResumen", lblResumen);
    card.getProperties().put("lblNotas", lblNotas);

    card.getChildren().addAll(head, lblResumen, lblNotas, actions);
    return card;
  }

  private void updateCard(Node card, Pedido p) {
    Label lblTimer = (Label) card.getProperties().get("lblTimer");
    Label lblEstado = (Label) card.getProperties().get("lblEstado");
    Label lblResumen = (Label) card.getProperties().get("lblResumen");
    Label lblNotas = (Label) card.getProperties().get("lblNotas");

    if (lblTimer != null) lblTimer.setText(elapsedFrom(p.fecha_pedido));

    if (lblEstado != null) {
      lblEstado.setText(mapEstadoUi(p.estado));
      lblEstado.getStyleClass().removeIf(c -> c.startsWith("badge--"));
      lblEstado.getStyleClass().add(cssBadgeFor(p.estado));
    }
    if (lblResumen != null) lblResumen.setText(resumenProductos(p));
    if (lblNotas != null)   lblNotas.setText(notitas(p));
  }

  private void patchEstado(Integer idPedido, String nuevo) {
    if (idPedido == null) { showError("ID de pedido inválido."); return; }

    setLoading(true);
    Task<Void> t = new Task<>() {
      @Override protected Void call() throws Exception {
        String body = "{\"estado\":\"" + nuevo + "\"}";
        var resp = api.patchJson("/pedidos?id_pedido=eq." + idPedido, body);
        int code = resp.statusCode();
        if (code == 200 || code == 204) return null;
        throw new RuntimeException("HTTP " + code + "\n" + resp.body());
      }
    };
    t.setOnSucceeded(e -> {
      info("Estado actualizado a " + nuevo);
      refrescar();
    });
    t.setOnFailed(e -> {
      setLoading(false);
      showError("No se pudo actualizar.\n" + (t.getException()!=null? t.getException().getMessage() : ""));
    });
    new Thread(t, "kds-patch").start();
  }

  /* =================== Helpers =================== */

  private void setLoading(boolean v){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> setLoading(v)); return; }
    if (loader != null) loader.setVisible(v);
    if (grid   != null) grid.setDisable(v);
    if (cbFiltro != null) cbFiltro.setDisable(v);
    if (chkAutoRefresh != null) chkAutoRefresh.setDisable(v);
    if (btnRefrescar != null) btnRefrescar.setDisable(v);
  }

  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }
  private static String safe(Object o){ return o==null? "—" : String.valueOf(o); }

  private String elapsedFrom(String fechaPedidoIso) {
    try {
      if (fechaPedidoIso == null || fechaPedidoIso.isBlank()) return "—";
      // Acepta '2025-10-23T10:20:00+00:00' o sin zona
      OffsetDateTime odt = OffsetDateTime.parse(normalizeIso(fechaPedidoIso));
      long secs = Duration.between(odt, OffsetDateTime.now(ZoneId.systemDefault())).getSeconds();
      if (secs < 0) secs = 0;
      long mm = secs / 60;
      long ss = secs % 60;
      return String.format("%02d:%02d min", mm, ss);
    } catch (Exception e) {
      return "—";
    }
  }

  private String normalizeIso(String s) {
    // Si viene “2025-10-23 10:20:00”, lo pasamos a “2025-10-23T10:20:00Z” (asume UTC)
    if (s.contains("T")) return s;
    return s.trim().replace(' ', 'T') + "Z";
  }

  private String resumenProductos(Pedido p) {
    // Traer 1–2 productos rápido (sin bloquear mucho). Si quieres exactitud, podríamos cachear por pedido.
    try {
      String path = "/detalle_pedido?select=" + enc("producto,cantidad,precio_unitario") +
          "&id_pedido=eq." + p.id_pedido + "&order=id_detalle.asc&limit=3";
      var resp = api.getResp(path);
      if (resp.statusCode()<200 || resp.statusCode()>=300) return "—";
      List<DetallePedido> dets = om.readValue(resp.body(), new TypeReference<List<DetallePedido>>(){});
      if (dets.isEmpty()) return "—";
      String items = dets.stream()
          .map(d -> (safe(d.producto) + " x" + (d.cantidad==null?0:d.cantidad)))
          .collect(Collectors.joining("; "));
      if (dets.size() >= 3) items += " …";
      return items;
    } catch (Exception e) {
      return "—";
    }
  }

  private String notitas(Pedido p) {
    // Si tu esquema tiene notas en pedido o detalle, aquí las pones. Por ahora placeholder:
    String fecha = safe(p.fecha_entrega);
    String hora  = safe(p.hora_entrega);
    if (!fecha.equals("—") || !hora.equals("—")) {
      return "Entrega: " + fecha + (hora.equals("—") ? "" : (" " + hora));
    }
    return "Sin notas";
  }

  private String mapEstadoUi(String estado) {
    if (estado == null) return "—";
    return switch (estado) {
      case "COCINA"     -> "PENDIENTE";
      case "COCINANDO"  -> "EN_PROCESO";
      case "COCINADO"   -> "LISTO";
      default           -> estado;
    };
  }

  private String cssBadgeFor(String estado) {
    if (estado == null) return "badge--gris";
    return switch (estado) {
      case "COCINA"     -> "badge--naranja";
      case "COCINANDO"  -> "badge--azul";
      case "COCINADO"   -> "badge--verde";
      default           -> "badge--gris";
    };
  }

  private void showError(String m){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(m)); return; }
    new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait();
  }
  private void info(String m){
    if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> info(m)); return; }
    new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait();
  }

  /* === Ciclo de vida === */
  @FXML
  public void onClose() {
    destroyed = true;
    if (autoTask != null) autoTask.cancel(true);
    if (scheduler != null) scheduler.shutdownNow();
  }
}
