// src/main/java/com/mycompany/pasteleria/desktop/model/Categoria.java
package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Categoria {
    public Integer id_categoria;
    public String  nombre;
    public String  descripcion;

    public Integer getId_categoria() { return id_categoria; }
    public String  getNombre()       { return nombre; }
    public String  getDescripcion()  { return descripcion; }

    // útil para ComboBox (productos lo usa)
    @Override
    public String toString() {
        return (nombre == null || nombre.isBlank())
                ? "—"
                : nombre;
    }
}
