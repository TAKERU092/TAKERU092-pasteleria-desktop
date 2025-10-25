package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Categoria {
  public Integer id_categoria;
  public String  nombre;
  public String  descripcion;

  // Getters para JavaFX PropertyValueFactory
  public Integer getId_categoria() { return id_categoria; }
  public String getNombre() { return nombre; }
  public String getDescripcion() { return descripcion; }
}
