// src/main/java/com/mycompany/pasteleria/desktop/net/ApiClient.java
package com.mycompany.pasteleria.desktop.net;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * ApiClient para Supabase PostgREST:
 *  - Base URL: https://<project>.supabase.co/rest/v1
 *  - Autenticación: apikey + Bearer <anonKey|service role>
 *  - JSON por defecto, con Prefer adecuados cuando corresponde.
 *
 * Mantiene compatibilidad con el uso actual del proyecto y añade utilidades opcionales.
 */
public class ApiClient {

  /* ===================== Config por defecto ===================== */
  private static final String USER_AGENT = "Pasteleria-Desktop/1.0 (+https://artia-ica.example)";
  private static final Duration CONNECT_TIMEOUT_DEFAULT = Duration.ofSeconds(10);
  private static final Duration REQ_TIMEOUT_DEFAULT     = Duration.ofSeconds(20);

  /* ===================== Estado ===================== */
  private final HttpClient http;
  private final String baseRest;   // https://xxx.supabase.co/rest/v1
  private final String bearer;     // normalmente anonKey
  private final String apiKey;     // normalmente anonKey

  // Opcional: si usas schema != public, setéalos (ej. "public", "myschema")
  private final String acceptProfile;   // header Accept-Profile
  private final String contentProfile;  // header Content-Profile

  private final Duration requestTimeout;

  /* ===================== Constructores ===================== */

  /** Constructor sencillo (como el tuyo). */
  public ApiClient(String supabaseUrl, String anonKey) {
    this(supabaseUrl, anonKey, anonKey, "public", "public", CONNECT_TIMEOUT_DEFAULT, REQ_TIMEOUT_DEFAULT);
  }

  /** Constructor avanzado (por si luego quieres schema o token distinto). */
  public ApiClient(
      String supabaseUrl,
      String apiKey,
      String bearerToken,
      String acceptProfile,
      String contentProfile,
      Duration connectTimeout,
      Duration requestTimeout
  ) {
    String base = Objects.requireNonNull(supabaseUrl, "supabaseUrl").endsWith("/")
        ? supabaseUrl : supabaseUrl + "/";
    this.baseRest = base + "rest/v1";
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    this.bearer = Objects.requireNonNull(bearerToken, "bearerToken");
    this.acceptProfile = acceptProfile;   // puede ser null o "public"
    this.contentProfile = contentProfile; // puede ser null o "public"
    this.requestTimeout = requestTimeout == null ? REQ_TIMEOUT_DEFAULT : requestTimeout;

    this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(connectTimeout == null ? CONNECT_TIMEOUT_DEFAULT : connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /* ===================== Firmas existentes (compatibles) ===================== */

  /** GET simple. */
  public HttpResponse<String> getResp(String pathAndQuery) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .header("Accept", "application/json")
        .GET()
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  /** GET con Prefer: count=exact para leer Content-Range (total). */
  public HttpResponse<String> getRespWithCount(String pathAndQuery) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .header("Accept", "application/json")
        .header("Prefer", "count=exact")
        .GET()
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  /** PATCH JSON. */
  public HttpResponse<String> patchJson(String pathAndQuery, String json) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .header("Content-Type", "application/json")
        .header("Prefer", "return=representation")
        .method("PATCH", HttpRequest.BodyPublishers.ofString(json == null ? "" : json))
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  /** POST JSON (útil para inserts). */
  public HttpResponse<String> postJson(String pathAndQuery, String json) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .header("Content-Type", "application/json")
        .header("Prefer", "return=representation")
        .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json))
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  /** PUT JSON (reemplazo). */
  public HttpResponse<String> putJson(String pathAndQuery, String json) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .header("Content-Type", "application/json")
        .header("Prefer", "return=representation")
        .PUT(HttpRequest.BodyPublishers.ofString(json == null ? "" : json))
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  /** DELETE. */
  public HttpResponse<String> delete(String pathAndQuery) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .header("Accept", "application/json")
        .DELETE()
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  /* ===================== Sobrecargas con reintentos (safe para GET) ===================== */

  /** GET con reintentos (429/5xx). */
  public HttpResponse<String> getResp(String pathAndQuery, int maxRetries) throws IOException, InterruptedException {
    return sendWithRetries(() -> base(pathAndQuery)
        .header("Accept", "application/json")
        .GET()
        .timeout(requestTimeout)
        .build(), maxRetries);
  }

  /** GET count=exact con reintentos. */
  public HttpResponse<String> getRespWithCount(String pathAndQuery, int maxRetries) throws IOException, InterruptedException {
    return sendWithRetries(() -> base(pathAndQuery)
        .header("Accept", "application/json")
        .header("Prefer", "count=exact")
        .GET()
        .timeout(requestTimeout)
        .build(), maxRetries);
  }

  /* ===================== Extras opcionales ===================== */

  /** HEAD (útil para comprobar existencia sin traer cuerpo). */
  public HttpResponse<Void> head(String pathAndQuery) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.discarding());
  }

  /** GET raw bytes (por si alguna vez traes binarios). */
  public HttpResponse<byte[]> getRespBytes(String pathAndQuery) throws IOException, InterruptedException {
    HttpRequest req = base(pathAndQuery)
        .GET()
        .timeout(requestTimeout)
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
  }

  /** Lanza IOException si status no es 2xx, con contexto. */
  public static void require2xx(HttpResponse<?> resp, String context) throws IOException {
    int sc = resp.statusCode();
    if (sc < 200 || sc >= 300) {
      String body = (resp instanceof HttpResponse<?> r && r.body() instanceof String s) ? s : "";
      String msg = "HTTP " + sc + " en " + context + (body != null && !body.isBlank() ? (": " + body) : "");
      throw new IOException(msg);
    }
  }

  /** Crea un clon del ApiClient con nuevos timeouts. */
  public ApiClient withTimeouts(Duration connectTimeout, Duration requestTimeout) {
    return new ApiClient(
        this.baseRest.replace("/rest/v1",""),  // reconstruye base supabase URL
        this.apiKey,
        this.bearer,
        this.acceptProfile,
        this.contentProfile,
        connectTimeout == null ? CONNECT_TIMEOUT_DEFAULT : connectTimeout,
        requestTimeout == null ? REQ_TIMEOUT_DEFAULT : requestTimeout
    );
  }

  /** Crea un clon del ApiClient usando un bearer distinto (p.ej. service role). */
  public ApiClient serviceRole(String serviceToken) {
    return new ApiClient(
        this.baseRest.replace("/rest/v1",""),
        this.apiKey,
        Objects.requireNonNull(serviceToken, "serviceToken"),
        this.acceptProfile,
        this.contentProfile,
        CONNECT_TIMEOUT_DEFAULT,
        this.requestTimeout
    );
  }

  /* ===================== Internals ===================== */

  private HttpRequest.Builder base(String pathAndQuery) {
    String path = sanitize(pathAndQuery);
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseRest + path))
        .header("apikey", apiKey)
        .header("Authorization", "Bearer " + bearer)
        .header("User-Agent", USER_AGENT);

    // Si algún día usas schemas distintos a "public":
    if (acceptProfile != null && !acceptProfile.isBlank()) {
      b.header("Accept-Profile", acceptProfile);
    }
    if (contentProfile != null && !contentProfile.isBlank()) {
      b.header("Content-Profile", contentProfile);
    }
    return b;
  }

  /** Asegura que el path empiece con '/' y no duplique '/' */
  private static String sanitize(String pathAndQuery) {
    if (pathAndQuery == null || pathAndQuery.isBlank()) return "/";
    String p = pathAndQuery.trim();
    if (!p.startsWith("/")) p = "/" + p;
    return p;
  }

  /* =============== Retries con backoff exponencial (GETs) =============== */

  @FunctionalInterface
  private interface RequestSupplier { HttpRequest get() throws Exception; }

  private HttpResponse<String> sendWithRetries(RequestSupplier supplier, int maxRetries)
      throws IOException, InterruptedException {
    int attempt = 0;
    long sleepMs = 200L;

    while (true) {
      attempt++;
      final HttpRequest req;
      try {
        req = supplier.get();
      } catch (Exception e) {
        if (e instanceof IOException ioe) throw ioe;
        throw new IOException("No se pudo construir la solicitud", e);
      }

      try {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int sc = resp.statusCode();

        if (sc >= 200 && sc < 300) return resp;

        if (attempt <= Math.max(1, maxRetries) && isRetriable(sc)) {
          Thread.sleep(sleepMs);
          sleepMs = Math.min(2000L, sleepMs * 2);
          continue;
        }
        return resp; // devolver el error si no se reintenta más
      } catch (IOException ioe) {
        if (attempt > Math.max(1, maxRetries)) throw ioe;
        Thread.sleep(sleepMs);
        sleepMs = Math.min(2000L, sleepMs * 2);
      }
    }
  }

  private static boolean isRetriable(int status) {
    return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
  }

  /* ===================== Utilidades de ayuda ===================== */

  /** URL-encode (UTF-8). */
  public static String urlEncode(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }

  /**
   * Construye una query string ordenada y segura (sin "?").
   * Ejemplo:
   *   buildQuery(Map.of("select","id,nombre","limit","50"))
   *   -> "select=id%2Cnombre&limit=50"
   */
  public static String buildQuery(Map<String, String> params) {
    if (params == null || params.isEmpty()) return "";
    StringJoiner sj = new StringJoiner("&");
    // Conserva orden si se pasa LinkedHashMap
    for (Map.Entry<String, String> e : (params instanceof LinkedHashMap ? params.entrySet() : new LinkedHashMap<>(params).entrySet())) {
      String k = urlEncode(e.getKey());
      String v = urlEncode(e.getValue());
      sj.add(k + "=" + v);
    }
    return sj.toString();
  }

  /* ===================== Range helper (opcional) ===================== */

  /** Si alguna vez quieres paginar por header Range en vez de limit/offset. */
  public static String rangeHeader(int offset, int limit) {
    if (offset < 0) offset = 0;
    if (limit <= 0) limit = 1;
    int end = offset + limit - 1;
    return "items=" + offset + "-" + end;
  }

  /** Ejemplo de uso Range:
   *  var req = base("/cliente?select=id,nombre")
   *    .header("Range-Unit", "items")
   *    .header("Range", ApiClient.rangeHeader(0, 20))
   *    .header("Prefer", "count=exact")
   *    .GET()
   *    .timeout(requestTimeout)
   *    .build();
   */
}

