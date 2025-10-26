// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/AsignarDeliveryController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Diálogo para asignar un repartidor a un pedido.
 * - Muestra datos del cliente/dirección (solo lectura).
 * - Carga repartidores activos desde delivery_person.
 * - Devuelve resultado vía propiedades del Stage:
 *   result_ok, id_pedido, id_delivery, hora_salida.
 */
public class AsignarDeliveryController {

  // --- UI (coinciden con AsignarDelivery.fxml) ---
  @FXML private Label lblCliente, lblTelefono, lblDireccion, lblDistrito, lblReferencia;
  @FXML private ComboBox<DeliveryItem> cbDelivery;
  @FXML private TextField txtHoraSalida;

  // --- Estado / API ---
  private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
  private final ObjectMapper om = new ObjectMapper();

  private int idPedido;

  /** Item para el ComboBox (id + nombre completo). */
  public record DeliveryItem(int id, String nombre) {
    @Override public String toString(){ return nombre; }
  }

  /** Cargado por el caller (PedidosController) con los datos del pedido/cliente. */
  public void setPedidoData(int idPedido,
                            String nombreCompleto,
                            String telefono,
                            String direccion,
                            String distrito,
                            String referencia) {
    this.idPedido = idPedido;

    lblCliente.setText(blank(nombreCompleto) ? "—" : nombreCompleto);
    lblTelefono.setText(blank(telefono) ? "—" : telefono);
    lblDireccion.setText(blank(direccion) ? "—" : direccion);
    lblDistrito.setText(blank(distrito) ? "—" : distrito);
    lblReferencia.setText(blank(referencia) ? "—" : referencia);

    // Sugerir hora actual HH:mm
    txtHoraSalida.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));

    cargarRepartidoresActivos();
  }

  private void cargarRepartidoresActivos() {
    Task<List<Map<String,Object>>> t = new Task<>() {
      @Override protected List<Map<String, Object>> call() throws Exception {
        // Usa getRespWithCount (tu ApiClient no expone get())
        String path = "/delivery_person?select=id_delivery,nombre,apellido,activo&activo=eq.true&order=nombre.asc";
        var resp = api.getRespWithCount(path);
        if (resp.statusCode()<200 || resp.statusCode()>=300) {
          throw new RuntimeException("HTTP "+resp.statusCode()+"\n"+resp.body());
        }
        return om.readValue(resp.body(), new TypeReference<List<Map<String,Object>>>(){});
      }
    };
    t.setOnSucceeded(e -> {
      var items = t.getValue().stream().map(m -> {
        int id = Integer.parseInt(String.valueOf(m.get("id_delivery")));
        String nom = String.valueOf(m.getOrDefault("nombre","")).trim();
        String ape = String.valueOf(m.getOrDefault("apellido","")).trim();
        return new DeliveryItem(id, (nom+" "+ape).trim());
      }).toList();
      cbDelivery.setItems(FXCollections.observableArrayList(items));
      if (!items.isEmpty()) cbDelivery.getSelectionModel().selectFirst();
    });
    t.setOnFailed(e -> show("No se pudieron cargar repartidores:\n"+t.getException().getMessage(), Alert.AlertType.ERROR));
    new Thread(t,"delivery-load").start();
  }

  @FXML
  public void cancelar() { close(false, -1, null); }

  @FXML
  public void confirmar() {
    var sel = cbDelivery.getSelectionModel().getSelectedItem();
    if (sel == null) { show("Selecciona un delivery.", Alert.AlertType.WARNING); return; }

    String hora = txtHoraSalida.getText()==null? "" : txtHoraSalida.getText().trim();
    if (!hora.matches("^\\d{2}:\\d{2}$")) { show("Hora inválida. Usa HH:mm", Alert.AlertType.WARNING); return; }

    close(true, sel.id(), hora);
  }

  private void close(boolean ok, Integer idDelivery, String hora) {
    Stage st = (Stage) cbDelivery.getScene().getWindow();
    st.getProperties().put("result_ok", ok);
    st.getProperties().put("id_pedido", idPedido);
    if (ok) {
      st.getProperties().put("id_delivery", idDelivery);
      st.getProperties().put("hora_salida", hora);
    }
    st.close();
  }

  private static boolean blank(String s){ return s==null || s.isBlank(); }
  private void show(String m, Alert.AlertType t){ new Alert(t, m, ButtonType.OK).showAndWait(); }
}
