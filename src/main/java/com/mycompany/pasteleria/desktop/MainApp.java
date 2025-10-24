package com.mycompany.pasteleria.desktop;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public class MainApp extends Application {

  private static final Logger LOG = Logger.getLogger(MainApp.class.getName());

  @Override
  public void start(Stage stage) {
    // Captura excepciones no manejadas para evitar "pantalla en blanco" silenciosa
    Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
      LOG.log(Level.SEVERE, "Excepción no capturada en hilo " + t.getName(), e);
      showError("Error no capturado", e);
    });

    try {
      // 1) Ubicar FXML (falla inmediata si no existe)
      URL fxmlUrl = getClass().getResource("/com/mycompany/pasteleria/desktop/view/MainView.fxml");
      if (fxmlUrl == null) {
        throw new IllegalStateException("No se encontró MainView.fxml en /com/mycompany/pasteleria/desktop/view/");
      }

      // 2) Cargar FXML con FXMLLoader (permite obtener controller)
      FXMLLoader loader = new FXMLLoader(fxmlUrl);
      Parent root = loader.load();

      // Opcional: acceder al controller
      Object controller = loader.getController();
      LOG.info(() -> "Controller cargado: " + (controller != null ? controller.getClass().getName() : "<null>"));

      // 3) Crear escena
      Scene scene = new Scene(root, 1200, 720);

      // 4) CSS (no obligatorio)
      URL cssUrl = getClass().getResource("/com/mycompany/pasteleria/desktop/app.css");
      if (cssUrl != null) {
        scene.getStylesheets().add(cssUrl.toExternalForm());
        LOG.info("CSS aplicado: " + cssUrl);
      } else {
        LOG.warning("No se encontró app.css (continuando sin estilos externos).");
      }

      // 5) Mostrar
      stage.setTitle("Pastelería");
      stage.setScene(scene);
      stage.show();

    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Error cargando el FXML", e);
      showError("No se pudo cargar la vista", e);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Error inesperado en start()", e);
      showError("Error inesperado", e);
    }
  }

  private void showError(String header, Throwable ex) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Error");
    alert.setHeaderText(header);
    String msg = ex.getMessage();
    alert.setContentText((msg != null && !msg.isBlank()) ? msg : ex.getClass().getName());
    alert.showAndWait();
    // Si prefieres cerrar la app cuando hay un error crítico:
    // Platform.exit();
  }

  public static void main(String[] args) {
    // Sugerencia: valida recursos críticos antes de lanzar
    Objects.requireNonNull(
        MainApp.class.getResource("/com/mycompany/pasteleria/desktop/view/MainView.fxml"),
        "Falta MainView.fxml en resources");
    launch(args);
  }
}

