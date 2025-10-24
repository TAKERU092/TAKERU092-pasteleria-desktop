// src/main/java/com/mycompany/pasteleria/desktop/model/Pedido.java
package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Pedido: compatible con Jackson por campos públicos.
 * Mejores prácticas:
 * - Ignora campos desconocidos (resiliencia a cambios en el backend).
 * - Enum de estado con "fromRaw" tolerante.
 * - Helpers seguros para nombre completo, total BigDecimal y fechas/horas.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Pedido {

  // === Campos mapeados desde Supabase/PostgREST ===
  public Integer id_pedido;
  public Integer id_cliente;
  /** timestamp ISO (ej. "2025-10-23T15:30:00") */
  public String  fecha_pedido;
  /** date ISO (ej. "2025-10-24") */
  public String  fecha_entrega;
  /** time ISO (ej. "16:30:00") */
  public String  hora_entrega;
  /** REGISTRADO, COCINA, COCINANDO, COCINADO, ENTREGADO, CANCELADO */
  public String  estado;
  /** numeric/text; lo dejamos String para no romper nada */
  public String  total;

  /** Relación embebida: cliente:cliente(nombre,apellido) */
  public ClienteEmb cliente;

  @JsonIgnore
  public Estado getEstadoEnum() {
    return Estado.fromRaw(estado);
  }

  /** Total como BigDecimal seguro (devuelve 0 si null/blank). */
  @JsonIgnore
  public BigDecimal getTotalBD() {
    try {
      if (total == null || total.isBlank()) return BigDecimal.ZERO;
      // Cambia coma por punto por si viniera con coma decimal
      String norm = total.replace(',', '.').trim();
      return new BigDecimal(norm);
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }

  /** Nombre completo del cliente (o "—" si no hay datos). */
  @JsonIgnore
  public String getNombreCliente() {
    if (cliente == null) return "—";
    String n = safe(cliente.nombre);
    String a = safe(cliente.apellido);
    String full = (n + " " + a).trim();
    return full.isEmpty() ? "—" : full;
  }

  /** Fecha pedido como LocalDateTime (null si no parsea). */
  @JsonIgnore
  public LocalDateTime getFechaPedidoDT() {
    try {
      if (fecha_pedido == null || fecha_pedido.isBlank()) return null;
      // Acepta "yyyy-MM-ddTHH:mm[:ss]"
      String s = fecha_pedido.replace(' ', 'T');
      return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s);
    } catch (Exception ignore) { return null; }
  }

  /** Fecha entrega como LocalDate (null si no parsea). */
  @JsonIgnore
  public LocalDate getFechaEntregaD() {
    try {
      if (fecha_entrega == null || fecha_entrega.isBlank()) return null;
      return LocalDate.parse(fecha_entrega);
    } catch (Exception ignore) { return null; }
  }

  /** Hora entrega como LocalTime (null si no parsea). */
  @JsonIgnore
  public LocalTime getHoraEntregaT() {
    try {
      if (hora_entrega == null || hora_entrega.isBlank()) return null;
      String s = hora_entrega;
      // Normaliza "HH:mm" -> "HH:mm:00"
      if (s.length() == 5) s = s + ":00";
      return LocalTime.parse(s);
    } catch (Exception ignore) { return null; }
  }

  private static String safe(String s) { return s == null ? "" : s; }

  // ======= Tipos auxiliares =======

  public static class ClienteEmb {
    public String nombre;
    public String apellido;
  }

  public enum Estado {
    REGISTRADO, COCINA, COCINANDO, COCINADO, ENTREGADO, CANCELADO, DESCONOCIDO;

    public static Estado fromRaw(String raw) {
      if (raw == null) return DESCONOCIDO;
      switch (raw.trim().toUpperCase()) {
        case "REGISTRADO": return REGISTRADO;
        case "COCINA":     return COCINA;
        case "COCINANDO":  return COCINANDO;
        case "COCINADO":   return COCINADO;
        case "ENTREGADO":  return ENTREGADO;
        case "CANCELADO":  return CANCELADO;
        default:           return DESCONOCIDO;
      }
    }
  }

  // Opcional: equals/hashCode por id_pedido (útil para selecciones en UI)
  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Pedido)) return false;
    Pedido p = (Pedido) o;
    return id_pedido != null && id_pedido.equals(p.id_pedido);
  }
  @Override public int hashCode() {
    return id_pedido != null ? id_pedido.hashCode() : 0;
  }

  @Override public String toString() {
    return "Pedido{id=" + id_pedido + ", cliente=" + getNombreCliente() + ", estado=" + estado + "}";
  }
}
