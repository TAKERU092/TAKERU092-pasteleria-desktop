// src/main/java/com/mycompany/pasteleria/desktop/controller/DashboardController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.Cliente;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class DashboardController {

  // ---------- UI ----------
  @FXML private TextField txtBuscar;
  @FXML private TableView<Row> tblClientes;
  @FXML private TableColumn<Row, String> colNombre, colEmail, colTelefono, colDireccion;
  @FXML private Label lblTotal, lblPagina, lblFiltro, lblRango;
  @FXML private ProgressIndicator loader;

  // (opcionales si existen en tu FXML; si no, ignóralos)
  @FXML private Button btnBuscar, btnRefrescar, btnPrev, btnNext, btnExport;

  // ---------- Estado ----------
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();
  private List<Cliente> cache = List.of();
  private int limit = 20;
  private int offset = 0;
  private int total = 0;
  private String filtro = "";

  // referencia a la tarea en curso para poder cancelarla
  private Task<List<Cliente>> consultaTask;

  // ---------- Row para la tabla ----------
  public static class Row {
    private final String nombre, email, telefono, direccion;
    public Row(String n, String e, String t, String d){ this.nombre=n; this.email=e; this.telefono=t; this.direccion=d; }
    public String getNombre(){ return nombre; }
    public String getEmail(){ return email; }
    public String getTelefono(){ return telefono; }
    public String getDireccion(){ return direccion; }
  }

  // ---------- Ciclo de vida ----------
  @FXML
  public void initialize() {
    colNombre.setCellValueFactory(new PropertyValueFactory<>("nombre"));
    colEmail.setCellValueFactory(new PropertyValueFactory<>("email"));
    colTelefono.setCellValueFactory(new PropertyValueFactory<>("telefono"));
    colDireccion.setCellValueFactory(new PropertyValueFactory<>("direccion"));

    // Doble click en fila -> alerta con detalle
    tblClientes.setRowFactory(tv -> {
      TableRow<Row> row = new TableRow<>();
      row.setOnMouseClicked(evt -> {
        if (evt.getClickCount() == 2 && !row.isEmpty()) {
          Row r = row.getItem();
          new Alert(Alert.AlertType.INFORMATION,
              "Cliente\n\nNombre: " + r.getNombre() +
              "\nEmail: " + r.getEmail() +
              "\nTeléfono: " + r.getTelefono() +
              "\nDirección: " + r.getDireccion(),
              ButtonType.OK).showAndWait();
        }
      });
      return row;
    });

    // Enter en el buscador
    txtBuscar.setOnAction(e -> buscar());

    // Carga inicial
    refrescar();
  }

  // ---------- Acciones (desde FXML) ----------
  @FXML public void buscar() {
    this.filtro = s(txtBuscar.getText());
    this.offset = 0;
    consultar();
  }

  @FXML public void refrescar() {
    this.offset = 0; // mantiene filtro actual
    consultar();
  }

  @FXML public void paginaAnterior() {
    if (offset - limit >= 0) {
      offset -= limit;
      consultar();
    }
  }

  @FXML public void paginaSiguiente() {
    if (offset + limit < total) {
      offset += limit;
      consultar();
    }
  }

  @FXML public void exportarCSV() {
    if (tblClientes.getItems() == null || tblClientes.getItems().isEmpty()) {
      alert("No hay datos para exportar.");
      return;
    }
    FileChooser fc = new FileChooser();
    fc.setTitle("Guardar clientes (CSV)");
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
    fc.setInitialFileName("clientes.csv");
    File f = fc.showSaveDialog(tblClientes.getScene().getWindow());
    if (f == null) return;

    try (var bw = new BufferedWriter(new FileWriter(f, false))) {
      // header
      bw.write("Nombre,Email,Telefono,Direccion");
      bw.newLine();
      // rows
      for (Row r : tblClientes.getItems()) {
        bw.write(csv(r.getNombre()) + "," + csv(r.getEmail()) + "," + csv(r.getTelefono()) + "," + csv(r.getDireccion()));
        bw.newLine();
      }
      info("Exportado: " + f.getAbsolutePath());
    } catch (Exception ex) {
      alert("Error al exportar: " + ex.getMessage());
    }
  }

  // ---------- Consulta a Supabase ----------
  private void consultar() {
    cancelar(consultaTask);
    setLoading(true);

    // Columnas del SELECT
    String select = enc(String.join(",",
        "id_cliente","nombre","apellido","email","contrasena","telefono",
        "distrito","direccion","referencia","fecha_registro","fecha_cumple"
    ));

    // Filtro de búsqueda (solo se codifican los VALORES; no la clave 'or=')
    String search = "";
    if (!filtro.isBlank()) {
      String term = filtro.replace("%","").trim();
      // codifica solo el término para ilike
      String t = enc("*" + term + "*");
      String or = "or=("
          + "nombre.ilike."   + t + ","
          + "apellido.ilike." + t + ","
          + "email.ilike."    + t + ","
          + "telefono.ilike." + t
          + ")";
      search = "&" + or; // <-- importante: NO codificar 'or=' entero
    }

    String path = "/cliente?select=" + select
        + "&order=apellido.asc&order=nombre.asc"
        + "&limit=" + limit + "&offset=" + offset
        + search;

    // Task que trae datos y total (Content-Range). Asumo que ApiClient añade Prefer: count=exact.
    consultaTask = new Task<>() {
      @Override protected List<Cliente> call() throws Exception {
        var resp = api.getRespWithCount(path);
        int code = resp.statusCode();
        String body = resp.body();
        if (code < 200 || code >= 300) {
          throw new RuntimeException("HTTP " + code + " al consultar Supabase.\nPath: " + path + "\nBody:\n" + body);
        }

        // Total desde Content-Range: "0-19/123"
        String cr = resp.headers().firstValue("Content-Range").orElse("");
        total = parseTotal(cr).orElse(0);

        List<Cliente> clientes = om.readValue(body, new TypeReference<List<Cliente>>(){});
        cache = clientes;
        return clientes;
      }
    };

    consultaTask.setOnSucceeded(ev -> {
      List<Cliente> clientes = consultaTask.getValue();
      pintar(clientes);
      actualizarKpis();
      setLoading(false);
    });

    consultaTask.setOnFailed(ev -> {
      setLoading(false);
      Throwable ex = Optional.ofNullable(consultaTask.getException()).orElse(new RuntimeException("Error desconocido"));
      showError("No se pudo cargar clientes.\n" + ex.getMessage());
    });

    new Thread(consultaTask, "supabase-clientes").start();
  }

  private void pintar(List<Cliente> clientes) {
    var rows = clientes.stream().map(c -> {
      String nombreCompleto = (s(c.nombre) + " " + s(c.apellido)).trim();
      String dir = join(s(c.distrito), s(c.direccion), s(c.referencia));
      return new Row(nombreCompleto.isBlank()? "—" : nombreCompleto,
                     s(c.email).isBlank()? "—" : s(c.email),
                     s(c.telefono).isBlank()? "—" : s(c.telefono),
                     dir);
    }).collect(Collectors.toList());

    tblClientes.setItems(FXCollections.observableArrayList(rows));

    // Rango mostrado
    int from = total == 0 ? 0 : offset + 1;
    int to = Math.min(offset + limit, Math.max(total, offset + rows.size()));
    lblRango.setText("Mostrando " + from + "–" + to + " de " + total);
  }

  private void actualizarKpis() {
    lblTotal.setText(String.valueOf(total));
    int paginaActual = (total == 0) ? 0 : (offset / limit) + 1;
    int paginas = (total == 0) ? 0 : ((total - 1) / limit) + 1;
    lblPagina.setText(paginaActual + " / " + paginas);
    lblFiltro.setText(filtro.isBlank() ? "—" : filtro);
  }

  // ---------- Helpers ----------
  private void setLoading(boolean v) {
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(() -> setLoading(v));
      return;
    }
    if (loader != null) loader.setVisible(v);
    if (tblClientes != null) tblClientes.setDisable(v);
    if (btnBuscar   != null) btnBuscar.setDisable(v);
    if (btnRefrescar!= null) btnRefrescar.setDisable(v);
    if (btnPrev     != null) btnPrev.setDisable(v);
    if (btnNext     != null) btnNext.setDisable(v);
    if (btnExport   != null) btnExport.setDisable(v);
    if (txtBuscar   != null) txtBuscar.setDisable(v);
  }

  private static String join(String distrito, String direccion, String referencia) {
    StringBuilder sb = new StringBuilder();
    if (!distrito.isBlank()) sb.append(distrito);
    if (!direccion.isBlank()) {
      if (sb.length() > 0) sb.append(" – ");
      sb.append(direccion);
    }
    if (!referencia.isBlank()) {
      if (sb.length() > 0) sb.append(" (").append(referencia).append(")");
      else sb.append(referencia);
    }
    return (sb.length() == 0) ? "—" : sb.toString();
  }

  private static String s(String x){ return x==null? "" : x; }
  private static String enc(String x){ return URLEncoder.encode(x, StandardCharsets.UTF_8); }

  private static Optional<Integer> parseTotal(String contentRange) {
    try {
      if (contentRange == null || !contentRange.contains("/")) return Optional.empty();
      String after = contentRange.substring(contentRange.indexOf('/')+1).trim();
      return Optional.of(Integer.parseInt(after));
    } catch (Exception e) { return Optional.empty(); }
  }

  private static String csv(String s) {
    String v = (s == null) ? "" : s;
    if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
      v = "\"" + v.replace("\"","\"\"") + "\"";
    }
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
  private void showError(String msg){ alert(msg); }

  private static void cancelar(Task<?> t) {
    if (t != null && t.isRunning()) t.cancel(true);
  }
}

