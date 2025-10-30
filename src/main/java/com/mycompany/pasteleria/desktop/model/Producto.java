package com.mycompany.pasteleria.desktop.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Producto {
  public Integer id_producto;
  public String  nombre;
  public String  descripcion;
  public String  precio;        // PostgREST puede devolver numérico -> Jackson lo coacciona a String
  public Integer stock;
  public Integer id_categoria;  // FK
  public String  estado;        // ACTIVO / INACTIVO
  public String  imagen_url;

  // Relación embebida: categoria:categorias(nombre)
  public Categoria categoria;

  /* ===== Getters robustos para TableView ===== */
  public Integer getId_producto(){ return id_producto; }
  public String  getNombre(){ return nombre; }
  public String  getDescripcion(){ return descripcion; }
  public String  getEstado(){ return estado; }
  public Integer getId_categoria(){ return id_categoria; }
  public String  getImagen_url(){ return imagen_url; }

  public String getNombreOrDash(){ return (nombre==null||nombre.isBlank()) ? "—" : nombre; }

  public int getStockSafe(){ return stock == null ? 0 : stock; }

  public boolean isActivo(){ return estado != null && estado.equalsIgnoreCase("ACTIVO"); }

  public BigDecimal getPrecioBD() {
    try { return new BigDecimal(precio == null ? "0" : precio.replace(",", ".")); }
    catch(Exception e){ return BigDecimal.ZERO; }
  }

  public String getNombreCategoria(){
    return (categoria != null && categoria.nombre != null && !categoria.nombre.isBlank())
        ? categoria.nombre : "—";
  }
}
