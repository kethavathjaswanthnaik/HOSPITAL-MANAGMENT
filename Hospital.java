import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds all hospital data in memory and enforces the hospital's rules.
 * Methods are synchronized because the HTTP server may handle requests on different threads.
 */
public final class Hospital {

    public static final double WARD_RATE_PER_DAY = 1500.0;

    /** Hourly consultation slots; 13:00 is the lunch hour. */
    public static final List<String> SLOTS =
            List.of("09:00", "10:00", "11:00", "12:00", "14:00", "15:00", "16:00", "17:00");

    private static final Set<String> GENDERS = Set.of("Male", "Female", "Other");

    private final Map<Integer, Doctor> doctors = new LinkedHashMap<>();
    private final Map<Integer, Patient> patients = new LinkedHashMap<>();
    private final List<Appointment> appointments = new ArrayList<>();
    private final List<Bill> bills = new ArrayList<>();

    private int nextPatientId = 1001;
    private int nextAppointmentId = 5001;
    private int nextBillId = 9001;

    public Hospital() {
        loadSampleData();
    }

    // ---------------------------------------------------------------- patients

    public synchronized Patient registerPatient(String name, String ageText, String gender,
                                                String phone, String condition) {
        name = clean(name);
        phone = clean(phone);
        condition = clean(condition);

        // Checks run in the same order as the fields on the form.
        if (!name.matches("[A-Za-z][A-Za-z .'-]{1,59}")) {
            throw new HospitalException("Enter the patient's full name using letters only.");
        }
        int age = parseAge(ageText);
        if (gender == null || !GENDERS.contains(gender)) {
            throw new HospitalException("Choose a gender.");
        }
        if (!phone.matches("\\d{10}")) {
            throw new HospitalException("Phone number must be exactly 10 digits.");
        }
        if (condition.isEmpty() || condition.length() > 80) {
            throw new HospitalException("Enter the reason for the visit (up to 80 characters).");
        }

        Patient patient = new Patient(nextPatientId++, name, age, gender, phone, condition);
        patients.put(patient.getId(), patient);
        return patient;
    }

    public synchronized Patient admitPatient(int patientId) {
        Patient patient = findPatient(patientId);
        patient.admit(LocalDate.now());
        return patient;
    }

    // ------------------------------------------------------------ appointments

    public synchronized Appointment bookAppointment(int patientId, int doctorId,
                                                    LocalDate date, String time) {
        Patient patient = findPatient(patientId);
        Doctor doctor = findDoctor(doctorId);

        if (date.isBefore(LocalDate.now())) {
            throw new HospitalException("Appointments cannot be booked for a past date.");
        }
        if (!SLOTS.contains(time)) {
            throw new HospitalException("Choose one of the available time slots.");
        }
        for (Appointment a : appointments) {
            if (!a.holdsSlot() || !a.getDate().equals(date) || !a.getTime().equals(time)) {
                continue;
            }
            if (a.getDoctorId() == doctorId) {
                throw new HospitalException(doctor.getName() + " is already booked at " + time
                        + " on " + date + ". Pick another slot.");
            }
            if (a.getPatientId() == patientId) {
                throw new HospitalException(patient.getName() + " already has an appointment at "
                        + time + " on " + date + ".");
            }
        }

        Appointment appointment = new Appointment(nextAppointmentId++, patientId, doctorId, date, time);
        appointments.add(appointment);
        return appointment;
    }

    public synchronized Appointment updateAppointment(int appointmentId, String action) {
        Appointment appointment = null;
        for (Appointment a : appointments) {
            if (a.getId() == appointmentId) {
                appointment = a;
            }
        }
        if (appointment == null) {
            throw new HospitalException("Appointment " + appointmentId + " was not found.");
        }
        switch (action) {
            case "complete" -> {
                if (appointment.getDate().isAfter(LocalDate.now())) {
                    throw new HospitalException("A future appointment cannot be completed yet.");
                }
                appointment.complete();
            }
            case "cancel" -> appointment.cancel();
            default -> throw new HospitalException("Unknown appointment action.");
        }
        return appointment;
    }

    // ----------------------------------------------------------------- billing

    /**
     * Bills every completed, unbilled consultation plus the ward stay (if admitted).
     * Billing an admitted patient also discharges them.
     */
    public synchronized Bill billPatient(int patientId) {
        Patient patient = findPatient(patientId);
        List<Appointment> visits = unbilledVisits(patientId);
        int wardDays = patient.wardDays(LocalDate.now());

        if (visits.isEmpty() && wardDays == 0) {
            throw new HospitalException(patient.getName()
                    + " has nothing to bill. Complete an appointment or admit the patient first.");
        }

        double consultationCharges = 0;
        for (Appointment visit : visits) {
            consultationCharges += findDoctor(visit.getDoctorId()).getConsultationFee();
            visit.markBilled();
        }

        Bill bill = new Bill(nextBillId++, patient.getId(), patient.getName(), LocalDate.now(),
                visits.size(), consultationCharges, wardDays, wardDays * WARD_RATE_PER_DAY);
        bills.add(bill);

        if (patient.isAdmitted()) {
            patient.discharge();
        }
        return bill;
    }

    private List<Appointment> unbilledVisits(int patientId) {
        List<Appointment> visits = new ArrayList<>();
        for (Appointment a : appointments) {
            if (a.getPatientId() == patientId
                    && a.getStatus() == Appointment.Status.COMPLETED && !a.isBilled()) {
                visits.add(a);
            }
        }
        return visits;
    }

    private double pendingAmount(Patient patient) {
        double total = patient.wardDays(LocalDate.now()) * WARD_RATE_PER_DAY;
        for (Appointment visit : unbilledVisits(patient.getId())) {
            total += findDoctor(visit.getDoctorId()).getConsultationFee();
        }
        return total;
    }

    // ------------------------------------------------------------ JSON for GET

    public synchronized String dashboardJson() {
        LocalDate today = LocalDate.now();
        int admitted = 0;
        for (Patient p : patients.values()) {
            if (p.isAdmitted()) {
                admitted++;
            }
        }
        int appointmentsToday = 0;
        for (Appointment a : appointments) {
            if (a.holdsSlot() && a.getDate().equals(today)) {
                appointmentsToday++;
            }
        }
        double revenue = 0;
        for (Bill b : bills) {
            revenue += b.getTotal();
        }
        return Json.object()
                .put("today", today.toString())
                .put("patients", patients.size())
                .put("admitted", admitted)
                .put("doctors", doctors.size())
                .put("appointmentsToday", appointmentsToday)
                .put("revenue", revenue)
                .put("wardRate", WARD_RATE_PER_DAY)
                .putRaw("slots", Json.stringArray(SLOTS))
                .build();
    }

    public synchronized String patientsJson() {
        List<Patient> newestFirst = new ArrayList<>(patients.values());
        Collections.reverse(newestFirst);
        List<Json> rows = new ArrayList<>();
        for (Patient p : newestFirst) {
            rows.add(p.toJson().put("pending", pendingAmount(p)));
        }
        return Json.array(rows);
    }

    public synchronized String doctorsJson() {
        List<Json> rows = new ArrayList<>();
        for (Doctor d : doctors.values()) {
            rows.add(d.toJson());
        }
        return Json.array(rows);
    }

    public synchronized String appointmentsJson() {
        List<Appointment> sorted = new ArrayList<>(appointments);
        sorted.sort(Comparator.comparing(Appointment::getDate).thenComparing(Appointment::getTime));
        List<Json> rows = new ArrayList<>();
        for (Appointment a : sorted) {
            Patient patient = findPatient(a.getPatientId());
            Doctor doctor = findDoctor(a.getDoctorId());
            rows.add(a.toJson()
                    .put("patientName", patient.getName())
                    .put("doctorName", doctor.getName())
                    .put("specialization", doctor.getSpecialization()));
        }
        return Json.array(rows);
    }

    public synchronized String billsJson() {
        List<Bill> newestFirst = new ArrayList<>(bills);
        Collections.reverse(newestFirst);
        List<Json> rows = new ArrayList<>();
        for (Bill b : newestFirst) {
            rows.add(b.toJson());
        }
        return Json.array(rows);
    }

    // ----------------------------------------------------------------- helpers

    private Patient findPatient(int id) {
        Patient patient = patients.get(id);
        if (patient == null) {
            throw new HospitalException("Patient " + id + " was not found.");
        }
        return patient;
    }

    private Doctor findDoctor(int id) {
        Doctor doctor = doctors.get(id);
        if (doctor == null) {
            throw new HospitalException("Doctor " + id + " was not found.");
        }
        return doctor;
    }

    private static int parseAge(String ageText) {
        int age;
        try {
            age = Integer.parseInt(clean(ageText));
        } catch (NumberFormatException e) {
            throw new HospitalException("Age must be a whole number.");
        }
        if (age < 0 || age > 120) {
            throw new HospitalException("Age must be between 0 and 120.");
        }
        return age;
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private void loadSampleData() {
        addDoctor(new Doctor(101, "Dr. Ananya Rao", 44, "9848011101", "Cardiology", 800));
        addDoctor(new Doctor(102, "Dr. Rahul Verma", 51, "9848011102", "Orthopaedics", 700));
        addDoctor(new Doctor(103, "Dr. Meera Iyer", 38, "9848011103", "Paediatrics", 500));
        addDoctor(new Doctor(104, "Dr. Imran Qureshi", 47, "9848011104", "General Medicine", 400));
        addDoctor(new Doctor(105, "Dr. Sneha Reddy", 35, "9848011105", "Dermatology", 600));

        Patient ravi = registerPatient("Ravi Kumar", "45", "Male", "9876501234", "Chest pain");
        Patient lakshmi = registerPatient("Lakshmi Devi", "62", "Female", "9876505678", "Seasonal fever");
        Patient arjun = registerPatient("Arjun Mehta", "8", "Male", "9876509012", "Skin allergy");

        // Ravi has been in a ward for two days, so his pending bill already includes ward charges.
        ravi.admit(LocalDate.now().minusDays(2));

        LocalDate today = LocalDate.now();
        Appointment seen = bookAppointment(lakshmi.getId(), 104, today, "09:00");
        seen.complete();
        bookAppointment(ravi.getId(), 101, today, "10:00");
        bookAppointment(arjun.getId(), 105, today, "11:00");
    }

    private void addDoctor(Doctor doctor) {
        doctors.put(doctor.getId(), doctor);
    }
}
