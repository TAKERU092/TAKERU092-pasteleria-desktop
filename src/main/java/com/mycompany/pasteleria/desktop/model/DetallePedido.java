// src/main/java/com/mycompany/pasteleria/desktop/model/DetallePedido.java
package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * DetallePedido: campos públicos (compatibles con Jackson) + helpers seguros.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DetallePedido {
  // === Mapeo directo desde Supabase/PostgREST ===
  public Integer id_detalle;
  public Integer id_pedido;
  /** id de producto (si lo usas en tu esquema) */
  public Integer id;
  public String  producto;
  public Integer cantidad;
  /** precio_unitario puede venir como texto/numérico -> lo mantenemos String */
  public String  precio_unitario;

  /* ===== Helpers seguros (no rompen tu uso actual) ===== */

  /** Cantidad como BigDecimal (0 si es null). */
  @JsonIgnore
  public BigDecimal getCantidadBD() {
    return cantidad == null ? BigDecimal.ZERO : new BigDecimal(cantidad.toString());
  }

  /** Precio unitario como BigDecimal (0 si no parsea). Acepta coma decimal. */
  @JsonIgnore
  public BigDecimal getPrecioUnitarioBD() {
    try {
      if (precio_unitario == null || precio_unitario.isBlank()) return BigDecimal.ZERO;
      String norm = precio_unitario.replace(',', '.').trim();
      return new BigDecimal(norm);
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }

  /** Subtotal = cantidad * precio_unitario (redondeado a 2 decimales). */
  @JsonIgnore
  public BigDecimal getSubtotalBD() {
    return getPrecioUnitarioBD().multiply(getCantidadBD()).setScale(2, RoundingMode.HALF_UP);
  }

  /** Formateo dinero S/ con 2 decimales. */
  @JsonIgnore
  public String money(BigDecimal v) {
    if (v == null) v = BigDecimal.ZERO;
    return "S/ " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** Precio unitario formateado. */
  @JsonIgnore
  public String getPrecioUnitarioFmt() {
    return money(getPrecioUnitarioBD());
  }

  /** Subtotal formateado. */
  @JsonIgnore
  public String getSubtotalFmt() {
    return money(getSubtotalBD());
  }

  @Override public String toString() {
    return "DetallePedido{id_detalle=" + id_detalle +
        ", id_pedido=" + id_pedido +
        ", idProd=" + id +
        ", producto='" + (producto == null ? "" : producto) + '\'' +
        ", cantidad=" + cantidad +
        ", pu=" + precio_unitario + '}';
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DetallePedido)) return false;
    DetallePedido d = (DetallePedido) o;
    return id_detalle != null && id_detalle.equals(d.id_detalle);
  }

  @Override public int hashCode() {
    return id_detalle != null ? id_detalle.hashCode() : 0;
  }
}
