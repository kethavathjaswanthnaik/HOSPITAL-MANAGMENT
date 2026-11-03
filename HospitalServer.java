import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts a lightweight HTTP server on port 7070.
 *   GET  /                  serves index.html and style.css
 *   GET  /api/...           returns JSON (dashboard, patients, doctors, appointments, bills)
 *   POST /api/...           performs an action using form-encoded fields
 */
public class HospitalServer {

    private static final int PORT = 7070;
    private static final Hospital hospital = new Hospital();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/", HospitalServer::handleApi);
        server.createContext("/", HospitalServer::handleStatic);
        server.start();

        System.out.println("==================================");
        System.out.println("   LIFELINE HOSPITAL IS ONLINE");
        System.out.println("==================================");
        System.out.println("Open: http://localhost:" + PORT);
    }

    // ------------------------------------------------------------ static files

    private static void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        String type = switch (path) {
            case "/index.html" -> "text/html; charset=utf-8";
            case "/style.css" -> "text/css; charset=utf-8";
            default -> null;
        };
        Path file = type == null ? null : Paths.get(path.substring(1));
        if (!exchange.getRequestMethod().equals("GET") || file == null || !Files.exists(file)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(exchange, 200, type, Files.readAllBytes(file));
    }

    // -------------------------------------------------------------------- API

    private static void handleApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            String json = switch (method) {
                case "GET" -> handleGet(path);
                case "POST" -> handlePost(path, parseForm(readBody(exchange)));
                default -> null;
            };
            if (json == null) {
                sendJson(exchange, 404, Json.object().put("error", "Unknown request.").build());
            } else {
                sendJson(exchange, 200, json);
            }
        } catch (HospitalException e) {
            sendJson(exchange, 400, Json.object().put("error", e.getMessage()).build());
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, Json.object().put("error", "Something went wrong on the server.").build());
        }
    }

    private static String handleGet(String path) {
        return switch (path) {
            case "/api/dashboard" -> hospital.dashboardJson();
            case "/api/patients" -> hospital.patientsJson();
            case "/api/doctors" -> hospital.doctorsJson();
            case "/api/appointments" -> hospital.appointmentsJson();
            case "/api/bills" -> hospital.billsJson();
            default -> null;
        };
    }

    private static String handlePost(String path, Map<String, String> form) {
        switch (path) {
            case "/api/patients": {
                Patient p = hospital.registerPatient(
                        text(form, "name"),
                        text(form, "age"),
                        text(form, "gender"),
                        text(form, "phone"),
                        text(form, "condition"));
                return message(p.getName() + " registered with patient ID " + p.getId() + ".");
            }
            case "/api/appointments": {
                Appointment a = hospital.bookAppointment(
                        number(form, "patientId", "Choose a patient."),
                        number(form, "doctorId", "Choose a doctor."),
                        date(form, "date"),
                        text(form, "time"));
                return message("Appointment booked for " + a.getTime() + " on " + a.getDate() + ".");
            }
            case "/api/appointments/status": {
                Appointment a = hospital.updateAppointment(
                        number(form, "id", "Appointment ID is missing."),
                        text(form, "action"));
                return message("Appointment " + a.getStatus().name().toLowerCase() + ".");
            }
            case "/api/admit": {
                Patient p = hospital.admitPatient(number(form, "patientId", "Choose a patient."));
                return message(p.getName() + " admitted to the ward.");
            }
            case "/api/bill": {
                Bill bill = hospital.billPatient(number(form, "patientId", "Choose a patient."));
                String note = bill.getWardDays() > 0 ? " Patient discharged." : "";
                return Json.object()
                        .put("message", "Bill " + bill.getId() + " generated for " + bill.getPatientName() + "." + note)
                        .putRaw("bill", bill.toJson().build())
                        .build();
            }
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String message(String text) {
        return Json.object().put("message", text).build();
    }

    private static String text(Map<String, String> form, String key) {
        return form.getOrDefault(key, "").trim();
    }

    private static int number(Map<String, String> form, String key, String errorMessage) {
        try {
            return Integer.parseInt(text(form, key));
        } catch (NumberFormatException e) {
            throw new HospitalException(errorMessage);
        }
    }

    private static LocalDate date(Map<String, String> form, String key) {
        try {
            return LocalDate.parse(text(form, key));
        } catch (DateTimeParseException e) {
            throw new HospitalException("Choose a valid date.");
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new HashMap<>();
        if (body.isBlank()) {
            return fields;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            fields.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
