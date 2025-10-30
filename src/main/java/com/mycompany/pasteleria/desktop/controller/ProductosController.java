// RUTA: src/main/java/com/mycompany/pasteleria/desktop/controller/ProductosController.java
package com.mycompany.pasteleria.desktop.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.pasteleria.desktop.config.AppConfig;
import com.mycompany.pasteleria.desktop.model.Categoria;
import com.mycompany.pasteleria.desktop.model.Producto;
import com.mycompany.pasteleria.desktop.net.ApiClient;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ProductosController {

    // ---------- top ----------
    @FXML private ComboBox<Categoria> cbCategoria;
    @FXML private TextField txtBuscar;
    @FXML private ToggleButton tglCards, tglTable;
    @FXML private Button btnBuscar, btnRefrescar, btnNuevo;

    // ---------- center ----------
    @FXML private ScrollPane cardsScroll;
    @FXML private TilePane   cardGrid;

    @FXML private TableView<Producto> tbl;
    @FXML private TableColumn<Producto, String> colId, colNombre, colCategoria, colPrecio, colStock, colEstado, colDesc;

    // ---------- bottom ----------
    @FXML private Button btnPrecio, btnStock, btnEstado, btnImagenes, btnExport;

    // ---------- loader ----------
    @FXML private ProgressIndicator loader;

    // ---------- http ----------
    private final ApiClient api = new ApiClient(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY);
    private final ObjectMapper om = new ObjectMapper();

    // datos en memoria
    private List<Producto> pagina = List.of();
    private String  filtroNombre      = "";
    private Integer filtroCategoriaId = null;

    private enum ViewMode { CARDS, TABLE }
    private ViewMode mode = ViewMode.CARDS;

    // ---------- layout tarjetas ----------
    private static final double MIN_CARD_W   = 200; 
    private static final double MAX_CARD_W   = 380;
    private static final double HGAP         = 12;
    private static final double GRID_PADDING = 20;
    private double lastViewportWidth = 0;

    private static final String CARD_BASE_STYLE =
            "-fx-background-color:white; -fx-background-radius:12; -fx-padding:10;" +
            "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.08), 8, 0, 0, 2);";
    private static final String CARD_SELECTED_STYLE =
            CARD_BASE_STYLE + " -fx-border-color:#2563eb; -fx-border-width:2; -fx-border-radius:12;";

    private final Map<Integer, VBox>      cardById = new HashMap<>();
    private final Map<Integer, ImageView> imgById  = new HashMap<>();
    private Producto selected = null;

    // imagen por defecto
    private static final String IMG_FALLBACK = "https://picsum.photos/seed/pasteleria-noimage/512/512";

    // =========================================================
    // init
    // =========================================================
    @FXML
    public void initialize() {

        // --- tabla ---
        tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        colId.setCellValueFactory(p -> new SimpleStringProperty(String.valueOf(p.getValue().id_producto)));
        colNombre.setCellValueFactory(new PropertyValueFactory<>("nombre"));
        colCategoria.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getNombreCategoria()));
        colPrecio.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getPrecioBD().setScale(2).toPlainString()));
        colStock.setCellValueFactory(p -> new SimpleStringProperty(String.valueOf(p.getValue().stock == null ? 0 : p.getValue().stock)));
        colEstado.setCellValueFactory(new PropertyValueFactory<>("estado"));
        colDesc.setCellValueFactory(new PropertyValueFactory<>("descripcion"));
        // esta columna absorbe el ancho sobrante
        colDesc.setMaxWidth(Double.MAX_VALUE);

        // buscar con enter
        txtBuscar.setOnAction(e -> buscar());

        // combo categorías
        cbCategoria.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Categoria c, boolean empty) {
                super.updateItem(c, empty);
                setText((empty || c == null) ? "Todas" : c.nombre);
            }
        });
        cbCategoria.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Categoria c, boolean empty) {
                super.updateItem(c, empty);
                setText((empty || c == null) ? null : c.nombre);
            }
        });
        cbCategoria.valueProperty().addListener((o, old, v) -> {
            filtroCategoriaId = (v == null) ? null : v.id_categoria;
            load();
        });

        // vista por defecto
        tglCards.setSelected(true);
        tglTable.setSelected(false);
        syncCenterVisibility();

        // responsivo en scroll
        cardsScroll.viewportBoundsProperty().addListener((obs, old, nb) -> {
            lastViewportWidth = nb.getWidth();
            updateGridLayout(lastViewportWidth);
        });
        cardGrid.widthProperty().addListener((obs, o, w) -> {
            lastViewportWidth = w.doubleValue();
            updateGridLayout(lastViewportWidth);
        });

        // cargar
        loadCategorias();
    }

    // =========================================================
    // cambio de vista
    // =========================================================
    @FXML
    public void switchToCards() {
        mode = ViewMode.CARDS;
        tglCards.setSelected(true);
        tglTable.setSelected(false);
        syncCenterVisibility();
        buildCards(pagina);
    }

    @FXML
    public void switchToTable() {
        mode = ViewMode.TABLE;
        tglTable.setSelected(true);
        tglCards.setSelected(false);
        syncCenterVisibility();
        tbl.setItems(FXCollections.observableArrayList(pagina));
    }

    private void syncCenterVisibility() {
        boolean cards = (mode == ViewMode.CARDS);
        cardsScroll.setVisible(cards);
        cardsScroll.setManaged(cards);
        tbl.setVisible(!cards);
        tbl.setManaged(!cards);
    }

    // =========================================================
    // acciones top
    // =========================================================
    @FXML
    public void buscar() {
        filtroNombre = (txtBuscar.getText() == null) ? "" : txtBuscar.getText().trim();
        load();
    }

    @FXML
    public void refrescar() { load(); }

    @FXML
    public void nuevo() { abrirForm(null); }

    // =========================================================
    // cargas
    // =========================================================
    private void loadCategorias() {
        setLoading(true);
        Task<Void> t = new Task<>() {
            @Override protected Void call() throws Exception {
                var resp = api.getResp("/categorias?select=" + enc("id_categoria,nombre") + "&order=nombre.asc&limit=200");
                int code = resp.statusCode();
                if (code >= 200 && code < 300) {
                    List<Categoria> list = om.readValue(resp.body(), new TypeReference<List<Categoria>>() {});
                    Platform.runLater(() -> {
                        List<Categoria> items = new ArrayList<>();
                        items.add(null); // "Todas"
                        items.addAll(list);
                        cbCategoria.setItems(FXCollections.observableArrayList(items));
                        cbCategoria.getSelectionModel().selectFirst();
                        setLoading(false);
                        load();
                    });
                } else {
                    Platform.runLater(() -> {
                        setLoading(false);
                        alert("HTTP " + code + "\n" + resp.body());
                    });
                }
                return null;
            }
        };
        new Thread(t, "load-categorias").start();
    }

    private void load() {
        setLoading(true);

        String select = enc("id_producto,nombre,descripcion,precio,stock,id_categoria,estado,imagen_url,categoria:categorias(nombre)");
        StringBuilder path = new StringBuilder("/productos?select=").append(select).append("&order=nombre.asc");

        // filtro por categoría (solo por id)
        if (filtroCategoriaId != null) {
            path.append("&id_categoria=eq.").append(filtroCategoriaId);
        }

        // filtro por texto: SOLO nombre y descripción
        if (filtroNombre != null && !filtroNombre.isBlank()) {
            String like = "*" + filtroNombre.trim() + "*";
            String v = URLEncoder.encode(like, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("%2A", "*");
            // ojo: aquí ya NO metemos categoria.nombre... porque eso te daba PGRST100
            path.append("&or=(nombre.ilike.").append(v)
                    .append(",descripcion.ilike.").append(v)
                    .append(")");
        }

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                var resp = api.getResp(path.toString());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) {
                    List<Producto> list = om.readValue(resp.body(), new TypeReference<List<Producto>>() {});
                    Platform.runLater(() -> {
                        pagina = list;
                        if (mode == ViewMode.CARDS) {
                            buildCards(list);
                        } else {
                            tbl.setItems(FXCollections.observableArrayList(list));
                        }

                        // mantener selección si se puede
                        if (selected != null) {
                            Producto keep = list.stream()
                                    .filter(p -> Objects.equals(p.id_producto, selected.id_producto))
                                    .findFirst()
                                    .orElse(null);
                            if (keep != null) setSelected(keep);
                            else clearSelected();
                        }

                        setLoading(false);
                    });
                } else {
                    Platform.runLater(() -> {
                        setLoading(false);
                        alert("HTTP " + code + "\n" + resp.body());
                    });
                }
                return null;
            }
        };
        new Thread(task, "load-productos").start();
    }

    // =========================================================
    // tarjetas
    // =========================================================
    private void buildCards(List<Producto> list) {
        cardGrid.getChildren().clear();
        cardById.clear();
        imgById.clear();
        if (list == null) return;

        for (Producto p : list) {
            VBox card = new VBox(8);
            card.setAlignment(Pos.TOP_LEFT);
            card.setPadding(new Insets(10));
            card.setMinWidth(MIN_CARD_W);
            card.setMaxWidth(MAX_CARD_W);
            card.setStyle(CARD_BASE_STYLE);

            ImageView iv = new ImageView(loadImageSafe(p.imagen_url));
            iv.setPreserveRatio(true);
            iv.setSmooth(true);

            Label name = new Label((p.nombre == null || p.nombre.isBlank()) ? "—" : p.nombre);
            name.setStyle("-fx-font-weight:700; -fx-font-size:13px;");
            name.setWrapText(true);

            Label cat = new Label(p.getNombreCategoria());
            cat.setStyle("-fx-opacity:0.75;");

            int stock = (p.stock == null) ? 0 : p.stock;
            HBox meta = new HBox(10,
                    new Label("S/ " + p.getPrecioBD().setScale(2).toPlainString()),
                    new Label("Stock: " + stock)
            );

            boolean activo = p.estado != null && p.estado.equalsIgnoreCase("ACTIVO");
            Label estado = new Label(p.estado == null ? "—" : p.estado.toUpperCase());
            estado.setStyle(
                    "-fx-background-radius:8; -fx-padding:2 8 2 8; -fx-font-size:11px; -fx-text-fill:white;" +
                            (activo ? "-fx-background-color:#16a34a;" : "-fx-background-color:#9ca3af;")
            );

            card.setOnMouseClicked(ev -> {
                setSelected(p);
                if (ev.getClickCount() == 2) {
                    editarProductoFull(p);
                }
            });

            // menú contextual
            ContextMenu cm = new ContextMenu();
            MenuItem itEdit   = new MenuItem("Editar...");
            MenuItem itPrecio = new MenuItem("Editar precio...");
            MenuItem itStock  = new MenuItem("Sumar stock...");
            MenuItem itEstado = new MenuItem("Cambiar estado...");
            MenuItem itImg    = new MenuItem("Imágenes...");
            itEdit.setOnAction(e -> editarProductoFull(p));
            itPrecio.setOnAction(e -> editarPrecioProducto(p));
            itStock.setOnAction(e -> sumarStockProducto(p));
            itEstado.setOnAction(e -> cambiarEstadoProducto(p));
            itImg.setOnAction(e -> gestionarImagenesProducto(p));
            cm.getItems().addAll(itEdit, new SeparatorMenuItem(),
                    itPrecio, itStock, itEstado, new SeparatorMenuItem(), itImg);
            card.setOnContextMenuRequested(e -> cm.show(card, e.getScreenX(), e.getScreenY()));

            card.getChildren().addAll(iv, name, cat, meta, estado);
            cardGrid.getChildren().add(card);

            cardById.put(p.id_producto, card);
            imgById.put(p.id_producto, iv);

            if (selected != null && Objects.equals(selected.id_producto, p.id_producto)) {
                card.setStyle(CARD_SELECTED_STYLE);
            }
        }

        double w = (lastViewportWidth > 0)
                ? lastViewportWidth
                : cardsScroll.getViewportBounds().getWidth();
        updateGridLayout(w);
    }

    private void setSelected(Producto p) {
        if (selected != null) {
            VBox old = cardById.get(selected.id_producto);
            if (old != null) old.setStyle(CARD_BASE_STYLE);
        }
        selected = p;
        VBox now = cardById.get(p.id_producto);
        if (now != null) now.setStyle(CARD_SELECTED_STYLE);

        if (tbl.isVisible() && tbl.getItems() != null) {
            for (Producto row : tbl.getItems()) {
                if (Objects.equals(row.id_producto, p.id_producto)) {
                    tbl.getSelectionModel().select(row);
                    tbl.scrollTo(row);
                    break;
                }
            }
        }
    }

    private void clearSelected() {
        if (selected != null) {
            VBox c = cardById.get(selected.id_producto);
            if (c != null) c.setStyle(CARD_BASE_STYLE);
        }
        selected = null;
    }

    /** Ajusta número de columnas y ancho de cada tarjeta. */
    private void updateGridLayout(double totalWidth) {
        if (totalWidth <= 0) return;

        double usable = Math.max(0, totalWidth - GRID_PADDING);
        // cuántas tarjetas de 220 + gap caben
        int cols = Math.max(1, (int) Math.floor((usable + HGAP) / (MIN_CARD_W + HGAP)));

        // ancho real que le toca a cada tarjeta
        double perWidth = (usable - (cols - 1) * HGAP) / cols;
        // lo forzamos a min / max
        perWidth = Math.max(MIN_CARD_W, Math.min(MAX_CARD_W, perWidth));

        cardGrid.setHgap(HGAP);
        cardGrid.setVgap(HGAP);
        cardGrid.setPrefColumns(cols);

        for (Map.Entry<Integer, VBox> e : cardById.entrySet()) {
            VBox card = e.getValue();
            card.setPrefWidth(perWidth);

            ImageView iv = imgById.get(e.getKey());
            if (iv != null) {
                double imgSize = Math.max(120, perWidth - 20);
                iv.setFitWidth(imgSize);
                iv.setFitHeight(imgSize);
            }
        }
    }

    // =========================================================
    // acciones bottom
    // =========================================================
    @FXML public void editarPrecio()   { Producto p = getAnySelection(); if (p != null) editarPrecioProducto(p); }
    @FXML public void sumarStock()     { Producto p = getAnySelection(); if (p != null) sumarStockProducto(p); }
    @FXML public void cambiarEstado()  { Producto p = getAnySelection(); if (p != null) cambiarEstadoProducto(p); }
    @FXML public void gestionarImagenes() { Producto p = getAnySelection(); if (p != null) gestionarImagenesProducto(p); }

    private Producto getAnySelection() {
        Producto row = tbl.getSelectionModel().getSelectedItem();
        if (row != null) return row;
        if (selected != null) return selected;
        alert("Selecciona un producto (click en una tarjeta o en la tabla).");
        return null;
    }

    private void editarProductoFull(Producto p) { abrirForm(p); }

    private void editarPrecioProducto(Producto p) {
        TextInputDialog d = new TextInputDialog(p.getPrecioBD().toPlainString());
        d.setTitle("Editar precio");
        d.setHeaderText(p.nombre);
        d.setContentText("Nuevo precio:");
        var res = d.showAndWait();
        if (res.isEmpty()) return;
        String precio = res.get().trim().replace(",", ".");
        patchJson("/productos?id_producto=eq." + p.id_producto,
                "{\"precio\":\"" + esc(precio) + "\"}",
                "Precio actualizado");
    }

    private void sumarStockProducto(Producto p) {
        TextInputDialog d = new TextInputDialog("1");
        d.setTitle("Sumar stock");
        d.setHeaderText(p.nombre);
        d.setContentText("Cantidad a sumar (entero):");
        var res = d.showAndWait();
        if (res.isEmpty()) return;
        int add;
        try { add = Integer.parseInt(res.get().trim()); }
        catch (Exception ex) { alert("Número inválido"); return; }
        int nuevo = (p.stock == null ? 0 : p.stock) + Math.max(0, add);
        patchJson("/productos?id_producto=eq." + p.id_producto,
                "{\"stock\":" + nuevo + "}",
                "Stock actualizado");
    }

    private void cambiarEstadoProducto(Producto p) {
        boolean activo = p.estado != null && p.estado.equalsIgnoreCase("ACTIVO");
        ChoiceDialog<String> dialog = new ChoiceDialog<>(activo ? "INACTIVO" : "ACTIVO", "ACTIVO", "INACTIVO");
        dialog.setTitle("Cambiar estado");
        dialog.setHeaderText(p.nombre);
        dialog.setContentText("Estado:");
        var res = dialog.showAndWait();
        if (res.isEmpty()) return;
        patchJson("/productos?id_producto=eq." + p.id_producto,
                "{\"estado\":\"" + esc(res.get()) + "\"}",
                "Estado actualizado");
    }

    private void gestionarImagenesProducto(Producto p) {
        try {
            var url = getClass().getResource("/com/mycompany/pasteleria/desktop/view/ImagenesProducto.fxml");
            if (url == null) throw new IllegalStateException("Falta ImagenesProducto.fxml");
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.setTitle("Imágenes — " + p.nombre);
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            Object ctrl = loader.getController();
            try {
                var m = ctrl.getClass().getMethod("setProducto", com.mycompany.pasteleria.desktop.model.Producto.class);
                m.invoke(ctrl, p);
            } catch (NoSuchMethodException ignored) {}
            dlg.getDialogPane().setContent(root);
            dlg.showAndWait();
        } catch (Exception ex) {
            alert("No se pudo abrir gestor de imágenes.\n" + ex.getMessage());
        }
    }

    private void abrirForm(Producto p) {
        try {
            var url = getClass().getResource("/com/mycompany/pasteleria/desktop/view/ProductoForm.fxml");
            if (url == null) throw new IllegalStateException("Falta ProductoForm.fxml");

            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.setTitle(p == null ? "Nuevo producto" : "Editar producto");
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();

            var ctrl = loader.<ProductoFormController>getController();
            ctrl.init(api, om, cargarCategoriasParaForm(), p);

            dlg.getDialogPane().setContent(root);
            var res = dlg.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.OK) {
                load();
            }
        } catch (Exception ex) {
            alert("No se pudo abrir el formulario.\n" + ex.getMessage());
        }
    }

    private List<Categoria> cargarCategoriasParaForm() {
        try {
            var resp = api.getResp("/categorias?select=" + enc("id_categoria,nombre") + "&order=nombre.asc&limit=200");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return om.readValue(resp.body(), new TypeReference<List<Categoria>>() {});
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    // =========================================================
    // http helpers
    // =========================================================
    private void patchJson(String path, String json, String okMsg) {
        setLoading(true);
        Task<Void> t = new Task<>() {
            @Override protected Void call() throws Exception {
                var resp = api.patchJson(path, json);
                int code = resp.statusCode();
                Platform.runLater(() -> {
                    if (code >= 200 && code < 300) {
                        info(okMsg);
                        load();
                    } else {
                        setLoading(false);
                        alert("HTTP " + code + "\n" + resp.body());
                    }
                });
                return null;
            }
        };
        new Thread(t, "patch-productos").start();
    }

    // =========================================================
    // imagen segura
    // =========================================================
    private Image loadImageSafe(String raw) {
        try {
            String url = (raw == null) ? "" : raw.trim();
            if (url.isEmpty()) {
                return new Image(IMG_FALLBACK, 200, 200, true, true, true);
            }
            // ruta local
            if (url.matches("^[A-Za-z]:\\\\.*")) {
                File f = new File(url);
                return new Image(f.toURI().toString(), 200, 200, true, true, true);
            }
            // recurso
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                var res = getClass().getResource(url.startsWith("/") ? url : "/" + url);
                if (res != null) return new Image(res.toExternalForm(), 200, 200, true, true, true);
                return new Image(IMG_FALLBACK, 200, 200, true, true, true);
            }
            // remota
            url = url.replace(" ", "%20");
            Image img = new Image(url, 200, 200, true, true, true);
            if (img.isError()) return new Image(IMG_FALLBACK, 200, 200, true, true, true);
            return img;
        } catch (Exception ex) {
            return new Image(IMG_FALLBACK, 200, 200, true, true, true);
        }
    }

    // =========================================================
    // util
    // =========================================================
    private static String enc(String x) { return URLEncoder.encode(x, StandardCharsets.UTF_8); }
    private static String esc(String x) { return x == null ? "" : x.replace("\"", "\\\""); }

    @FXML
    public void exportarCSV() {
        var items = (mode == ViewMode.TABLE) ? tbl.getItems() : FXCollections.observableArrayList(pagina);
        if (items == null || items.isEmpty()) {
            alert("No hay datos para exportar.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar productos (CSV)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
        fc.setInitialFileName("productos.csv");
        File f = fc.showSaveDialog(
                (cardsScroll != null && cardsScroll.getScene() != null) ? cardsScroll.getScene().getWindow() : null
        );
        if (f == null) return;

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f, false))) {
            bw.write("ID,Nombre,Categoría,Precio,Stock,Estado,Descripción,ImagenURL");
            bw.newLine();
            for (Producto p : items) {
                String id     = String.valueOf(p.id_producto);
                String nombre = (p.nombre == null) ? "" : p.nombre;
                String cat    = p.getNombreCategoria();
                String precio = p.getPrecioBD().setScale(2).toPlainString();
                String stock  = String.valueOf(p.stock == null ? 0 : p.stock);
                String estado = (p.estado == null) ? "" : p.estado;
                String desc   = (p.descripcion == null) ? "" : p.descripcion;
                String img    = (p.imagen_url == null) ? "" : p.imagen_url;

                bw.write(csv(id)+","+csv(nombre)+","+csv(cat)+","+csv(precio)+","+csv(stock)+","+csv(estado)+","+csv(desc)+","+csv(img));
                bw.newLine();
            }
            info("Exportado: " + f.getAbsolutePath());
        } catch (Exception ex) {
            alert("Error al exportar: " + ex.getMessage());
        }
    }

    private static String csv(String s) {
        String v = (s == null) ? "" : s;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            v = "\"" + v.replace("\"","\"\"") + "\"";
        }
        return v;
    }

    private void setLoading(boolean v) {
        if (loader != null) loader.setVisible(v);
        if (btnBuscar    != null) btnBuscar.setDisable(v);
        if (btnRefrescar != null) btnRefrescar.setDisable(v);
        if (btnNuevo     != null) btnNuevo.setDisable(v);
        if (txtBuscar    != null) txtBuscar.setDisable(v);
        if (cbCategoria  != null) cbCategoria.setDisable(v);
        if (tglCards     != null) tglCards.setDisable(v);
        if (tglTable     != null) tglTable.setDisable(v);
        if (tbl          != null) tbl.setDisable(v);
        if (cardsScroll  != null) cardsScroll.setDisable(v);
        if (btnPrecio    != null) btnPrecio.setDisable(v);
        if (btnStock     != null) btnStock.setDisable(v);
        if (btnEstado    != null) btnEstado.setDisable(v);
        if (btnImagenes  != null) btnImagenes.setDisable(v);
        if (btnExport    != null) btnExport.setDisable(v);
    }

    private void alert(String m) { new Alert(Alert.AlertType.ERROR, m, ButtonType.OK).showAndWait(); }
    private void info(String m)  { new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK).showAndWait(); }
}
