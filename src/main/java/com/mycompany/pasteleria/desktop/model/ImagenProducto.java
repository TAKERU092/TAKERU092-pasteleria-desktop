// src/main/java/com/mycompany/pasteleria/desktop/model/ImagenProducto.java
package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ImagenProducto {
  public Integer id_imagen;
  public Integer id_producto;
  public String  url;
  public Integer orden;
}
