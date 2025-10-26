package com.mycompany.pasteleria.desktop.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Modelo de la tabla "pagos".
 * Campos opcionales:
 *  - monto (si existe en tu tabla)
 *  - pedido (si haces join: pedido:pedidos(total,estado))
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Pago {
  public Integer id_pago;
  public Integer id_pedido;
  public String  metodo;
  public String  estado_pago;
  public String  comprobante_url;
  public String  fecha_registro;   // timestamptz ISO (lo dejamos como String)

  // Si tu tabla tiene columna NUMERIC/DECIMAL "monto", déjala así:
  public BigDecimal monto;         // puede venir null

  // Si en el SELECT haces join "pedido:pedidos(total,estado)", esto se llena solo:
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PedidoJoin {
    public BigDecimal total;       // total del pedido
    public String estado;          // estado del pedido
  }
  public PedidoJoin pedido;        // opcional (solo si haces el join)

  // ----- Helpers -----
  @JsonIgnore
  public BigDecimal getMontoOrZero() {
    return (monto == null) ? BigDecimal.ZERO : monto;
  }

  @JsonIgnore
  public static String money(BigDecimal v) {
    if (v == null) v = BigDecimal.ZERO;
    return "S/ " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
