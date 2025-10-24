// src/main/java/com/mycompany/pasteleria/desktop/model/Producto.java
package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Producto {
  public Integer id_producto;
  public String  nombre;
  public String  descripcion;
  public String  precio;        // numeric -> String (como vienes usando)
  public Integer stock;
  public Integer id_categoria;  // FK
  public String  estado;        // ACTIVO / INACTIVO (texto)

  // Embebido desde relación
  public Categoria categoria;   // categorias(nombre)

  public BigDecimal getPrecioBD() {
    try { return new BigDecimal(precio == null ? "0" : precio.replace(",", ".")); }
    catch(Exception e){ return BigDecimal.ZERO; }
  }

  public String getNombreCategoria(){
    return (categoria != null && categoria.nombre != null && !categoria.nombre.isBlank())
        ? categoria.nombre : "—";
  }
}

