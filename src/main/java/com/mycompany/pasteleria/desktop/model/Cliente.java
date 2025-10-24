// src/main/java/com/mycompany/pasteleria/desktop/model/Cliente.java
package com.mycompany.pasteleria.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Cliente: campos públicos (compatibles con Jackson) + helpers seguros.
 * - Ignora campos desconocidos (resiliente a cambios del backend).
 * - Parsers opcionales para fechas (timestamp y date).
 * - Helpers para nombre completo, email/telefono mostrables.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cliente {

  // === Mapeo directo (nombres exactos de columnas) ===
  public Integer id_cliente;
  public String  nombre;
  public String  apellido;
  public String  email;
  public String  contrasena;      // ojo: NO mostrar en UI
  public String  telefono;
  public String  distrito;
  public String  direccion;
  public String  referencia;
  /** timestamp con zona (ej. 2025-10-23T15:30:00+00:00) -> lo dejamos String para compatibilidad */
  public String  fecha_registro;
  /** date simple (ej. 2001-04-15) -> String para compatibilidad */
  public String  fecha_cumple;

  // === Helpers opcionales (no rompen tu uso actual) ===

  /** Nombre completo o "—" si no hay datos. */
  @JsonIgnore
  public String getNombreCompleto() {
    String n = safe(nombre), a = safe(apellido);
    String full = (n + " " + a).trim();
    return full.isEmpty() ? "—" : full;
  }

  /** Email para mostrar (o "—"). */
  @JsonIgnore
  public String getEmailSafe() {
    return isBlank(email) ? "—" : email;
  }

  /** Teléfono normalizado (solo dígitos y +), o "—". */
  @JsonIgnore
  public String getTelefonoFmt() {
    if (isBlank(telefono)) return "—";
    String t = telefono.replaceAll("[^0-9+]", "");
    return t.isEmpty() ? "—" : t;
  }

  /** Fecha de registro parseada como OffsetDateTime (o null si no parsea). */
  @JsonIgnore
  public OffsetDateTime getFechaRegistroDT() {
    try {
      if (isBlank(fecha_registro)) return null;
      return OffsetDateTime.parse(fecha_registro);
    } catch (Exception ignore) { return null; }
  }

  /** Fecha de registro corta “yyyy-MM-dd HH:mm” (o "—"). */
  @JsonIgnore
  public String getFechaRegistroCorta() {
    OffsetDateTime odt = getFechaRegistroDT();
    if (odt == null) return "—";
    return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }

  /** Fecha de cumpleaños como LocalDate (o null). */
  @JsonIgnore
  public LocalDate getFechaCumpleD() {
    try {
      if (isBlank(fecha_cumple)) return null;
      return LocalDate.parse(fecha_cumple);
    } catch (Exception ignore) { return null; }
  }

  /** Dirección unificada (Distrito – Dirección (Referencia)) o "—". */
  @JsonIgnore
  public String getDireccionFull() {
    String d = safe(distrito), dir = safe(direccion), ref = safe(referencia);
    StringBuilder sb = new StringBuilder();
    if (!d.isBlank()) sb.append(d);
    if (!dir.isBlank()) { if (sb.length() > 0) sb.append(" – "); sb.append(dir); }
    if (!ref.isBlank()) { if (sb.length() > 0) sb.append(" (").append(ref).append(")"); else sb.append(ref); }
    return sb.length() == 0 ? "—" : sb.toString();
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
  private static String safe(String s) { return s == null ? "" : s; }

  @Override public String toString() {
    return "Cliente{id=" + id_cliente + ", nombre='" + getNombreCompleto() + "', email='" + getEmailSafe() + "'}";
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Cliente)) return false;
    Cliente c = (Cliente) o;
    return id_cliente != null && id_cliente.equals(c.id_cliente);
  }
  @Override public int hashCode() {
    return id_cliente != null ? id_cliente.hashCode() : 0;
  }
}
