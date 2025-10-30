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

/**
 * Vista de PEDIDOS
 * - Tabla con MÁXIMO 7 columnas visibles (nosotros usamos 6).
 * - Filtro múltiple de estados.
 * - Búsqueda por número (server) o por cliente (client-side).
 * - Acciones: enviar a cocina, asignar delivery.
 */
public class PedidosController {

    // =================== CONSTANTES ===================
    private static final String ENDPOINT_PEDIDOS = "/pedidos";
    private static final String FXML_ASIGNAR_DELIVERY = "/com/mycompany/pasteleria/desktop/view/AsignarDelivery.fxml";

    // todos los estados que tú usas
    private static final List<String> ESTADOS_SOPORTADOS = List.of(
            "EN_REVISION",
            "PAGO_NO_VALIDO",
            "APROBADO",
            "EN_COCINA",
            "EN_PREPARACION",
            "ASIGNADO",
            "ENVIADO",
            "ENTREGADO",
            "SERVIDO",
            "NO_ENCONTRADO",
            "CANCELADO"
    );

    // estados que quieres ver por defecto al abrir
    private static final List<String> ESTADOS_POR_DEFECTO = List.of(
            "APROBADO", "EN_COCINA", "ASIGNADO", "ENVIADO", "ENTREGADO"
    );

    // =================== UI ===================
    @FXML private TextField txtBuscar;
    @FXML private MenuButton btnEstados;

    @FXML private TableView<Row> tbl;
    @FXML private TableColumn<Row, String> colNum, colCliente, colFecPed, colFecEnt, colHorEnt, colEstado;

    @FXML private Label lblRango;
    @FXML private ProgressIndicator loader;

    @FXML private Button btnBuscar, btnRefrescar, btnPrev, btnNext, btnToCocina, btnAsignar;

    // =================== ESTADO ===================
    private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
    private final ObjectMapper om = new ObjectMapper();

    private final LinkedHashSet<String> estados = new LinkedHashSet<>();
    private String filtroTexto = "";

    private int limit  = 20;
    private int offset = 0;
    private int total  = 0;

    private Task<List<Map<String,Object>>> consultaTask;
    private Task<Void> patchTask;

    // =================== DTO FILA ===================
    public static class Row {
        private final String num, cliente, fecPed, fecEnt, horEnt, estado;
        private final String direccion, distrito, referencia, telefono;

        public Row(String num,
                   String cliente,
                   String fecPed,
                   String fecEnt,
                   String horEnt,
                   String estado,
                   String direccion,
                   String distrito,
                   String referencia,
                   String telefono) {
            this.num = num;
            this.cliente = cliente;
            this.fecPed = fecPed;
            this.fecEnt = fecEnt;
            this.horEnt = horEnt;
            this.estado = estado;
            this.direccion = direccion;
            this.distrito = distrito;
            this.referencia = referencia;
            this.telefono = telefono;
        }

        public String getNum()        { return num; }
        public String getCliente()    { return cliente; }
        public String getFecPed()     { return fecPed; }
        public String getFecEnt()     { return fecEnt; }
        public String getHorEnt()     { return horEnt; }
        public String getEstado()     { return estado; }
        public String getDireccion()  { return direccion; }
        public String getDistrito()   { return distrito; }
        public String getReferencia() { return referencia; }
        public String getTelefono()   { return telefono; }
    }

    // =================== INIT ===================
    @FXML
    public void initialize() {
        // 1) columnas
        colNum.setCellValueFactory(c -> prop(c.getValue().getNum()));
        colCliente.setCellValueFactory(c -> prop(c.getValue().getCliente()));
        colFecPed.setCellValueFactory(c -> prop(c.getValue().getFecPed()));
        colFecEnt.setCellValueFactory(c -> prop(c.getValue().getFecEnt()));
        colHorEnt.setCellValueFactory(c -> prop(c.getValue().getHorEnt()));
        colEstado.setCellValueFactory(c -> prop(c.getValue().getEstado()));

        // 2) forzar máximo de columnas visibles
        ensureMaxColumns(7);

        // 3) menú de estados
        buildEstadosMenu(ESTADOS_SOPORTADOS);
        estados.addAll(ESTADOS_POR_DEFECTO);
        markMenuStates();

        // 4) buscar con ENTER
        if (txtBuscar != null) {
            txtBuscar.setOnAction(e -> buscar());
        }

        // 5) carga inicial
        refrescar();
    }

    // =================== ACCIONES BÁSICAS ===================
    @FXML public void buscar()           { filtroTexto = s(txtBuscar.getText()); offset = 0; consultar(); }
    @FXML public void refrescar()        { offset = 0; consultar(); }
    @FXML public void paginaAnterior()   { if (offset - limit >= 0) { offset -= limit; consultar(); } }
    @FXML public void paginaSiguiente()  { if (offset + limit < total) { offset += limit; consultar(); } }

    // =================== CONSULTA A SUPABASE ===================
    private void consultar() {
        cancelar(consultaTask);
        setLoading(true);

        // SELECT con embed de cliente
        String select =
                "id_pedido,fecha_pedido,fecha_entrega,hora_entrega,estado," +
                "direccion,distrito,referencia," +
                "cliente:cliente(nombre,apellido,telefono)";

        StringBuilder path = new StringBuilder(ENDPOINT_PEDIDOS)
                .append("?select=").append(enc(select))
                .append("&order=").append(enc("fecha_pedido.desc"))
                .append("&limit=").append(limit)
                .append("&offset=").append(offset);

        // filtro por estados -> /pedidos?estado=in.(APROBADO,EN_COCINA,...)
        if (!estados.isEmpty()) {
            // aquí NO rompemos nada, mandamos los valores tal cual
            String in = String.join(",", estados);
            path.append("&estado=in.(").append(in).append(")");
        }

        // filtro por número
        String term = s(filtroTexto).trim();
        boolean termEsNumero = !term.isBlank() && term.chars().allMatch(Character::isDigit);
        if (termEsNumero) {
            path.append("&id_pedido=eq.").append(term);
        }

        consultaTask = new Task<>() {
            @Override
            protected List<Map<String, Object>> call() throws Exception {
                var resp = api.getRespWithCount(path.toString());
                int code = resp.statusCode();
                String body = resp.body();
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code + "\n" + body);
                }

                // total
                String cr = resp.headers().firstValue("Content-Range").orElse("");
                total = parseTotal(cr).orElse(0);

                List<Map<String,Object>> list = om.readValue(body, new TypeReference<List<Map<String,Object>>>(){});

                // si el usuario puso texto, filtramos por nombre+apellido en memoria
                if (!term.isBlank() && !termEsNumero) {
                    String t = term.toLowerCase();
                    list = list.stream().filter(m -> {
                        Map<String,Object> c = castMap(m.get("cliente"));
                        String full = (s(c.get("nombre")) + " " + s(c.get("apellido"))).toLowerCase().trim();
                        return full.contains(t);
                    }).collect(Collectors.toList());
                }

                return list;
            }
        };

        consultaTask.setOnSucceeded(e -> {
            List<Map<String,Object>> datos = consultaTask.getValue();
            pintarTabla(datos, term, termEsNumero);
            setLoading(false);
            markMenuStates();
        });

        consultaTask.setOnFailed(e -> {
            setLoading(false);
            Throwable ex = Optional.ofNullable(consultaTask.getException())
                    .orElse(new RuntimeException("Error desconocido"));
            alert("No se pudo cargar pedidos.\n" + ex.getMessage());
        });

        new Thread(consultaTask, "pedidos-consulta").start();
    }

    private void pintarTabla(List<Map<String,Object>> datos, String term, boolean termEsNumero) {
        List<Row> rows = datos.stream().map(this::toRow).collect(Collectors.toList());

        tbl.setItems(FXCollections.observableArrayList(rows));

        // asegurar máximo columnas
        ensureMaxColumns(7);

        // rango mostrado
        if (lblRango != null) {
            if (!term.isBlank() && !termEsNumero) {
                int size = rows.size();
                lblRango.setText("Mostrando " + (size == 0 ? 0 : 1) + "–" + size + " de " + size);
            } else {
                int from = total == 0 ? 0 : offset + 1;
                int to   = Math.min(offset + limit, Math.max(total, offset + rows.size()));
                lblRango.setText("Mostrando " + from + "–" + to + " de " + total);
            }
        }
    }

    private Row toRow(Map<String,Object> m) {
        String id   = String.valueOf(m.get("id_pedido"));
        Map<String,Object> c = castMap(m.get("cliente"));
        String full = (s(c.get("nombre")) + " " + s(c.get("apellido"))).trim();
        String tel  = s(c.get("telefono"));

        return new Row(
                id,
                full.isBlank() ? "—" : full,
                fechaBonita(s(m.get("fecha_pedido"))),
                fechaBonita(s(m.get("fecha_entrega"))),
                horaBonita(s(m.get("hora_entrega"))),
                s(m.get("estado")).isBlank()? "—" : s(m.get("estado")),
                s(m.get("direccion")),
                s(m.get("distrito")),
                s(m.get("referencia")),
                tel
        );
    }

    // =================== CAMBIAR ESTADO ===================
    @FXML
    public void toEnCocina() {
        cambiarEstadoSeleccion("EN_COCINA");
    }

    private void cambiarEstadoSeleccion(String nuevo) {
        Row r = tbl.getSelectionModel().getSelectedItem();
        if (r == null) {
            alert("Selecciona un pedido.");
            return;
        }
        cancelar(patchTask);
        setLoading(true);

        patchTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                var resp = api.patchJson(
                        ENDPOINT_PEDIDOS + "?id_pedido=eq." + r.getNum(),
                        "{\"estado\":\"" + nuevo + "\"}"
                );
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw new RuntimeException("HTTP " + resp.statusCode() + "\n" + resp.body());
                }
                return null;
            }
        };

        patchTask.setOnSucceeded(e -> {
            info("Pedido → " + nuevo);
            consultar();
        });

        patchTask.setOnFailed(e -> {
            setLoading(false);
            Throwable ex = Optional.ofNullable(patchTask.getException())
                    .orElse(new RuntimeException("Error desconocido"));
            alert("No se pudo cambiar estado.\n" + ex.getMessage());
        });

        new Thread(patchTask, "pedido-estado").start();
    }

    // =================== ASIGNAR DELIVERY ===================
    @FXML
    public void asignarDelivery() {
        Row r = tbl.getSelectionModel().getSelectedItem();
        if (r == null) {
            alert("Selecciona un pedido.");
            return;
        }

        try {
            var url = getClass().getResource(FXML_ASIGNAR_DELIVERY);
            if (url == null) {
                alert("Falta el archivo AsignarDelivery.fxml");
                return;
            }

            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            AsignarDeliveryController ctrl = loader.getController();

            // pasamos datos de envío
            ctrl.setPedidoData(
                    Integer.parseInt(r.getNum()),
                    r.getCliente(), r.getTelefono(),
                    r.getDireccion(), r.getDistrito(), r.getReferencia()
            );

            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.setTitle("Asignar delivery");
            dlg.getDialogPane().setContent(root);
            dlg.showAndWait();

            // el diálogo deja los datos en las props de la ventana
            var win   = dlg.getDialogPane().getScene().getWindow();
            var props = win.getProperties();

            if (!Boolean.TRUE.equals(props.get("result_ok"))) {
                return; // usuario canceló
            }
            if (!props.containsKey("id_pedido") || !props.containsKey("id_delivery")) {
                alert("El diálogo no devolvió datos.");
                return;
            }

            int idPedido   = (int) props.get("id_pedido");
            int idDelivery = (int) props.get("id_delivery");
            String hora    = String.valueOf(props.getOrDefault("hora_salida", "00:00"));

            cancelar(patchTask);
            setLoading(true);

            patchTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    // 1) insertar asignación
                    String jsonAsign = String.format(Locale.ROOT,
                            "{\"id_pedido\": %d, \"id_delivery\": %d, \"hora_salida\": \"%s\", \"estado\": \"ASIGNADO\"}",
                            idPedido, idDelivery, hora
                    );
                    var respIns = api.postJson("/delivery_asignacion", jsonAsign);
                    if (respIns.statusCode() < 200 || respIns.statusCode() >= 300) {
                        throw new RuntimeException("Asignación HTTP " + respIns.statusCode() + "\n" + respIns.body());
                    }

                    // 2) marcar pedido
                    var respPed = api.patchJson(
                            ENDPOINT_PEDIDOS + "?id_pedido=eq." + idPedido,
                            "{\"estado\":\"ASIGNADO\"}"
                    );
                    if (respPed.statusCode() < 200 || respPed.statusCode() >= 300) {
                        throw new RuntimeException("Pedido HTTP " + respPed.statusCode() + "\n" + respPed.body());
                    }
                    return null;
                }
            };

            patchTask.setOnSucceeded(e -> {
                info("Pedido asignado.");
                consultar();
            });

            patchTask.setOnFailed(e -> {
                setLoading(false);
                Throwable ex = Optional.ofNullable(patchTask.getException())
                        .orElse(new RuntimeException("Error desconocido"));
                alert("No se pudo asignar delivery.\n" + ex.getMessage());
            });

            new Thread(patchTask, "asignar-delivery").start();

        } catch (Exception ex) {
            alert("No se pudo abrir el diálogo.\n" + ex.getMessage());
        }
    }

    // =================== MENÚ DE ESTADOS ===================
    private void buildEstadosMenu(List<String> opciones) {
        btnEstados.getItems().clear();
        for (String e : opciones) {
            CheckMenuItem it = new CheckMenuItem(e);
            it.setOnAction(ev -> {
                if (it.isSelected()) {
                    estados.add(e);
                } else {
                    estados.remove(e);
                }
                consultar();
            });
            btnEstados.getItems().add(it);
        }
    }

    private void markMenuStates() {
        for (var item : btnEstados.getItems()) {
            if (item instanceof CheckMenuItem cm) {
                cm.setSelected(estados.contains(cm.getText()));
            }
        }
        btnEstados.setText(estados.isEmpty()
                ? "Filtrar estados"
                : "Estados (" + estados.size() + ")");
    }

    // =================== HELPERS ===================
    private void setLoading(boolean v) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setLoading(v));
            return;
        }
        if (loader      != null) loader.setVisible(v);
        if (tbl         != null) tbl.setDisable(v);
        if (txtBuscar   != null) txtBuscar.setDisable(v);
        if (btnBuscar   != null) btnBuscar.setDisable(v);
        if (btnRefrescar!= null) btnRefrescar.setDisable(v);
        if (btnPrev     != null) btnPrev.setDisable(v);
        if (btnNext     != null) btnNext.setDisable(v);
        if (btnToCocina != null) btnToCocina.setDisable(v);
        if (btnAsignar  != null) btnAsignar.setDisable(v);
        if (btnEstados  != null) btnEstados.setDisable(v);
    }

    private static String s(Object x) {
        return x == null ? "" : String.valueOf(x);
    }

    private static String enc(String x) {
        return URLEncoder.encode(x, StandardCharsets.UTF_8);
    }

    private static Optional<Integer> parseTotal(String contentRange) {
        try {
            if (contentRange == null || !contentRange.contains("/")) return Optional.empty();
            String after = contentRange.substring(contentRange.indexOf('/') + 1).trim();
            return Optional.of(Integer.parseInt(after));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static final String[] MES = {
            "ene","feb","mar","abr","may","jun","jul","ago","sep","oct","nov","dic"
    };

    private static String fechaBonita(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        String s = iso;
        int t = s.indexOf('T');
        if (t > 0) s = s.substring(0, t);
        if (s.length() < 10) return s;
        String dd = s.substring(8, 10);
        int m = 1;
        try { m = Integer.parseInt(s.substring(5, 7)); } catch (Exception ignored) {}
        return dd + " " + MES[Math.max(1, Math.min(12, m)) - 1];
    }

    private static String horaBonita(String time) {
        if (time == null || time.isBlank()) return "—";
        if (time.length() >= 5) return time.substring(0, 5); // HH:mm:ss → HH:mm
        try {
            return LocalTime.parse(time).format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            return time;
        }
    }

    private void alert(String m) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> alert(m));
            return;
        }
        new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait();
    }

    private void info(String m) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> info(m));
            return;
        }
        new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait();
    }

    private static void cancelar(Task<?> t) {
        if (t != null && t.isRunning()) t.cancel(true);
    }

    /**
     * Garantiza que la tabla NO tenga más de "max" columnas visibles.
     * (Para cuando el SceneBuilder o el CSS mete una de relleno).
     */
    private void ensureMaxColumns(int max) {
        if (tbl == null) return;
        if (tbl.getColumns().size() > max) {
            tbl.getColumns().remove(max, tbl.getColumns().size());
        }
        // y fijamos las que sí queremos
        tbl.getColumns().setAll(colNum, colCliente, colFecPed, colFecEnt, colHorEnt, colEstado);
        tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> castMap(Object o) {
        return (o instanceof Map) ? (Map<String,Object>) o : Map.of();
    }

    private javafx.beans.property.SimpleStringProperty prop(String v) {
        return new javafx.beans.property.SimpleStringProperty(v);
    }
}
