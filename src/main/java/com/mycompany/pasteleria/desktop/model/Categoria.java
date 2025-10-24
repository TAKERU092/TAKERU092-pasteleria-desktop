// src/main/java/com/mycompany/pasteleria/desktop/model/Categoria.java
package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Categoria {
  public Integer id_categoria;
  public String  nombre;
  public String  descripcion;
}
