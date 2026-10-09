import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.*;

/**
 * Offline Student Clearance Management System
 * Java Swing GUI, NO database: data is stored in tab-separated text files in ./data
 *
 * Compile:  javac ClearanceApp.java
 * Run:      java ClearanceApp
 *
 * Departments: EDUCATION, INFORMATION TECHNOLOGY, CRIMINOLOGY,
 *              TOURISM MANAGEMENT, HOSPITALITY MANAGEMENT
 * Roles:       ADMIN, REGISTRAR, SIGNATORY, INSTRUCTOR, STUDENT
 *
 * Default logins (change them after first run):
 *   admin / admin123            registrar / registrar123
 *   education / education123    infotech / infotech123      criminology / criminology123
 *   tourism / tourism123        hospitality / hospitality123   (these are INSTRUCTOR accounts)
 *   Students: username = Student No., password = Student No. (until changed)
 */
public class ClearanceApp {

    // =====================================================================
    //  MODELS
    // =====================================================================
    static class User {
        int id, refId;               // refId = department id (SIGNATORY/INSTRUCTOR) or student id (STUDENT)
        String username, hash, role, name;
        User(int id, String username, String hash, String role, int refId, String name) {
            this.id = id; this.username = username; this.hash = hash;
            this.role = role; this.refId = refId; this.name = name;
        }
    }

    static class Student {
        int id, year;
        String no, first, last, course, section;
        boolean active = true;
        String full() { return last + ", " + first; }
    }

    static class Dept {
        int id, order;
        String name;
        @Override public String toString() { return name; }
    }

    static class Item {
        int deptId;
        String status = "PENDING", remarks = "", signedBy = "", date = "";
    }

    static class Clearance {
        int id, studentId;
        String term, controlNo = "", status = "IN_PROGRESS", dateIssued = "";
        List<Item> items = new ArrayList<>();
        Item item(int deptId) {
            for (Item i : items) if (i.deptId == deptId) return i;
            return null;
        }
        boolean allApproved() {
            return !items.isEmpty() && items.stream().allMatch(i -> i.status.equals("APPROVED"));
        }
        boolean issued() { return status.equals("CLEARED"); }
    }

    // =====================================================================
    //  FILE STORE (replaces the database): atomic tab-separated files
    // =====================================================================
    static class Store {
        static final Path DIR = Paths.get("data");
        static final Path BACKUPS = Paths.get("backups");
        static FileChannel lockChannel;
        static FileLock lock;

        /** Single-instance lock so two copies can't corrupt the data folder. */
        static boolean lock() {
            try {
                Files.createDirectories(DIR);
                lockChannel = FileChannel.open(DIR.resolve(".lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = lockChannel.tryLock();
                return lock != null;
            } catch (Exception e) {
                return false;
            }
        }

        static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
        }

        static String unesc(String s) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
                } else b.append(c);
            }
            return b.toString();
        }

        static String line(String[] r) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append('\t');
                sb.append(esc(r[i]));
            }
            return sb.append('\n').toString();
        }

        static List<String[]> read(String name) {
            List<String[]> rows = new ArrayList<>();
            Path p = DIR.resolve(name);
            if (!Files.exists(p)) return rows;
            try {
                for (String ln : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (ln.isEmpty()) continue;
                    String[] parts = ln.split("\t", -1);
                    for (int i = 0; i < parts.length; i++) parts[i] = unesc(parts[i]);
                    rows.add(parts);
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
            return rows;
        }

        /** Atomic save: write a temp file, then move it over the real file. */
        static void write(String name, List<String[]> rows) {
            try {
                Files.createDirectories(DIR);
                Path tmp = DIR.resolve(name + ".tmp"), dst = DIR.resolve(name);
                StringBuilder sb = new StringBuilder();
                for (String[] r : rows) sb.append(line(r));
                Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }

        static void append(String name, String[] row) {
            try {
                Files.createDirectories(DIR);
                Files.write(DIR.resolve(name), line(row).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }

    // =====================================================================
    //  IN-MEMORY DATA (loaded at start, saved after every change)
    // =====================================================================
    static class Data {
        static final List<User> users = new ArrayList<>();
        static final List<Student> students = new ArrayList<>();
        static final List<Dept> depts = new ArrayList<>();
        static final List<Clearance> clearances = new ArrayList<>();
        static final List<String[]> audit = new ArrayList<>();

        static final String[] DEPT_NAMES = {"EDUCATION", "INFORMATION TECHNOLOGY", "CRIMINOLOGY",
                "TOURISM MANAGEMENT", "HOSPITALITY MANAGEMENT"};
        static final String[] DEPT_USERS = {"education", "infotech", "criminology", "tourism", "hospitality"};

        static void load() {
            users.clear(); students.clear(); depts.clear(); clearances.clear(); audit.clear();
            try { Files.createDirectories(Store.DIR); } catch (IOException e) { throw new UncheckedIOException(e); }

            for (String[] r : Store.read("users.tsv"))
                users.add(new User(Integer.parseInt(r[0]), r[1], r[2], r[3], Integer.parseInt(r[4]), r[5]));
            for (String[] r : Store.read("students.tsv")) {
                Student s = new Student();
                s.id = Integer.parseInt(r[0]); s.no = r[1]; s.first = r[2]; s.last = r[3];
                s.course = r[4]; s.year = Integer.parseInt(r[5]); s.section = r[6];
                s.active = Boolean.parseBoolean(r[7]);
                students.add(s);
            }
            for (String[] r : Store.read("departments.tsv")) {
                Dept d = new Dept();
                d.id = Integer.parseInt(r[0]); d.name = r[1]; d.order = Integer.parseInt(r[2]);
                depts.add(d);
            }
            Map<Integer, Clearance> byId = new HashMap<>();
            for (String[] r : Store.read("clearances.tsv")) {
                Clearance c = new Clearance();
                c.id = Integer.parseInt(r[0]); c.studentId = Integer.parseInt(r[1]); c.term = r[2];
                c.controlNo = r[3]; c.status = r[4]; c.dateIssued = r[5];
                clearances.add(c); byId.put(c.id, c);
            }
            for (String[] r : Store.read("items.tsv")) {
                Clearance c = byId.get(Integer.parseInt(r[0]));
                if (c == null) continue;
                Item i = new Item();
                i.deptId = Integer.parseInt(r[1]); i.status = r[2]; i.remarks = r[3];
                i.signedBy = r[4]; i.date = r[5];
                c.items.add(i);
            }
            audit.addAll(Store.read("audit.tsv"));
            sortDepts();
            migrateOldDefaults();
            bootstrap();
            sortDepts();
        }

        static void sortDepts() { depts.sort(Comparator.comparingInt((Dept d) -> d.order).thenComparingInt(d -> d.id)); }

        /**
         * Older builds created Library/Accounting/Guidance/Department Head/Laboratory.
         * If those untouched defaults are found and no clearances exist yet, convert them
         * to the new departments and turn the default signatory accounts into instructors.
         */
        static void migrateOldDefaults() {
            String[] oldD = {"Library", "Accounting", "Guidance", "Department Head", "Laboratory"};
            String[] oldU = {"library", "accounting", "guidance", "depthead", "laboratory"};
            if (depts.size() != 5 || !clearances.isEmpty()) return;
            for (int i = 0; i < 5; i++) if (!depts.get(i).name.equals(oldD[i])) return;

            for (int i = 0; i < 5; i++) depts.get(i).name = DEPT_NAMES[i];
            saveDepts();

            for (User u : users) {
                for (int i = 0; i < 5; i++) {
                    if (u.role.equals("SIGNATORY") && u.username.equals(oldU[i]) && u.refId == depts.get(i).id
                            && Svc.verify(oldU[i] + "123", u.hash)) {      // only untouched default accounts
                        u.username = DEPT_USERS[i];
                        u.role = "INSTRUCTOR";
                        u.name = DEPT_NAMES[i] + " Instructor";
                        u.hash = Svc.hash(DEPT_USERS[i] + "123");
                    }
                }
            }
            saveUsers();
        }

        /** First run: default departments and accounts. */
        static void bootstrap() {
            if (depts.isEmpty()) {
                for (int i = 0; i < DEPT_NAMES.length; i++) {
                    Dept d = new Dept(); d.id = i + 1; d.name = DEPT_NAMES[i]; d.order = i + 1; depts.add(d);
                }
                saveDepts();
            }
            if (users.isEmpty()) {
                users.add(new User(1, "admin", Svc.hash("admin123"), "ADMIN", 0, "System Administrator"));
                users.add(new User(2, "registrar", Svc.hash("registrar123"), "REGISTRAR", 0, "Registrar Officer"));
                for (int i = 0; i < DEPT_USERS.length && i < depts.size(); i++)
                    users.add(new User(3 + i, DEPT_USERS[i], Svc.hash(DEPT_USERS[i] + "123"), "INSTRUCTOR",
                            depts.get(i).id, depts.get(i).name + " Instructor"));
                saveUsers();
            }
        }

        static Student student(int id) { for (Student s : students) if (s.id == id) return s; return null; }
        static Dept dept(int id) { for (Dept d : depts) if (d.id == id) return d; return null; }
        static Clearance clearance(int id) { for (Clearance c : clearances) if (c.id == id) return c; return null; }

        static void saveUsers() {
            Store.write("users.tsv", users.stream().map(u -> new String[]{"" + u.id, u.username, u.hash, u.role, "" + u.refId, u.name})
                    .collect(Collectors.toList()));
        }
        static void saveStudents() {
            Store.write("students.tsv", students.stream().map(s -> new String[]{"" + s.id, s.no, s.first, s.last,
                    s.course, "" + s.year, s.section, "" + s.active}).collect(Collectors.toList()));
        }
        static void saveDepts() {
            Store.write("departments.tsv", depts.stream().map(d -> new String[]{"" + d.id, d.name, "" + d.order})
                    .collect(Collectors.toList()));
        }
        static void saveClearances() {
            List<String[]> cr = new ArrayList<>(), ir = new ArrayList<>();
            for (Clearance c : clearances) {
                cr.add(new String[]{"" + c.id, "" + c.studentId, c.term, c.controlNo, c.status, c.dateIssued});
                for (Item i : c.items)
                    ir.add(new String[]{"" + c.id, "" + i.deptId, i.status, i.remarks, i.signedBy, i.date});
            }
            Store.write("clearances.tsv", cr);
            Store.write("items.tsv", ir);
        }
    }

    // =====================================================================
    //  SERVICES (business rules)
    // =====================================================================
    static class Svc {
        static User current;
        static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // ---- security ----
        static byte[] pbkdf2(String pw, byte[] salt) {
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 65000, 256)).getEncoded();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        static String hex(byte[] b) {
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format("%02x", x));
            return s.toString();
        }
        static byte[] unhex(String s) {
            byte[] b = new byte[s.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
            return b;
        }
        static String hash(String pw) {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            return hex(salt) + ":" + hex(pbkdf2(pw, salt));
        }
        static boolean verify(String pw, String stored) {
            try {
                String[] p = stored.split(":");
                return MessageDigest.isEqual(unhex(p[1]), pbkdf2(pw, unhex(p[0])));
            } catch (Exception e) { return false; }
        }

        static int nextId(Collection<Integer> ids) { return ids.stream().mapToInt(i -> i).max().orElse(0) + 1; }

        static User login(String username, String pw) {
            for (User u : Data.users)
                if (u.username.equalsIgnoreCase(username)) return verify(pw, u.hash) ? u : null;
            // first student login: account is created on the fly (username = password = Student No.)
            for (Student s : Data.students)
                if (s.active && s.no.equalsIgnoreCase(username) && pw.equals(s.no)) {
                    User nu = new User(nextId(Data.users.stream().map(x -> x.id).collect(Collectors.toList())),
                            s.no, hash(pw), "STUDENT", s.id, s.full());
                    Data.users.add(nu);
                    Data.saveUsers();
                    return nu;
                }
            return null;
        }

        static void log(String action) {
            String[] row = {LocalDateTime.now().format(TS), current == null ? "system" : current.username, action};
            Data.audit.add(row);
            Store.append("audit.tsv", row);
        }

        // ---- clearance workflow ----
        static String overall(Clearance c) {
            if (c.issued()) return "CLEARED";
            return c.allApproved() ? "READY" : "IN_PROGRESS";
        }

        /** Creates one clearance per active student for the term, with one item per department. */
        static int openClearance(String term) {
            int next = nextId(Data.clearances.stream().map(c -> c.id).collect(Collectors.toList()));
            int created = 0;
            for (Student s : Data.students) {
                if (!s.active) continue;
                boolean exists = Data.clearances.stream().anyMatch(c -> c.studentId == s.id && c.term.equals(term));
                if (exists) continue;
                Clearance c = new Clearance();
                c.id = next++; c.studentId = s.id; c.term = term;
                for (Dept d : Data.depts) { Item i = new Item(); i.deptId = d.id; c.items.add(i); }
                Data.clearances.add(c);
                created++;
            }
            if (created > 0) { Data.saveClearances(); log("Opened clearance '" + term + "' for " + created + " student(s)"); }
            return created;
        }

        static void updateItem(Clearance c, int deptId, String status, String remarks) {
            Item i = c.item(deptId);
            if (i == null) return;
            i.status = status; i.remarks = remarks;
            i.signedBy = current.name; i.date = LocalDate.now().toString();
            Student s = Data.student(c.studentId);
            log(status + " " + Data.dept(deptId).name + " for " + (s == null ? "?" : s.no) + " (" + c.term + ")");
        }

        static String issue(Clearance c) {
            c.controlNo = "CLR-" + LocalDate.now().getYear() + "-" + String.format("%05d", c.id);
            c.status = "CLEARED";
            c.dateIssued = LocalDate.now().toString();
            Data.saveClearances();
            log("Issued final clearance " + c.controlNo);
            return c.controlNo;
        }

        // ---- CSV student import: studentNo,firstName,lastName,course,yearLevel,section ----
        static int[] importCsv(Path f) throws IOException {
            int added = 0, skipped = 0;
            int next = nextId(Data.students.stream().map(s -> s.id).collect(Collectors.toList()));
            for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                ln = ln.replace("\uFEFF", "");
                if (ln.isBlank()) continue;
                String[] p = ln.split(",", -1);
                for (int i = 0; i < p.length; i++) p[i] = p[i].trim();
                if (p[0].equalsIgnoreCase("studentNo") || p[0].equalsIgnoreCase("student no")) continue;
                final String no = p[0];
                if (p.length < 6 || no.isEmpty() || Data.students.stream().anyMatch(s -> s.no.equalsIgnoreCase(no))) {
                    skipped++; continue;
                }
                Student s = new Student();
                s.id = next++; s.no = no; s.first = p[1]; s.last = p[2]; s.course = p[3]; s.section = p[5];
                try { s.year = Integer.parseInt(p[4]); } catch (NumberFormatException e) { s.year = 1; }
                Data.students.add(s);
                added++;
            }
            if (added > 0) { Data.saveStudents(); log("Imported " + added + " student(s) from CSV"); }
            return new int[]{added, skipped};
        }

        // ---- backup / restore (zip of the data folder) ----
        static Path backup(Path target) throws IOException {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(target));
                 DirectoryStream<Path> ds = Files.newDirectoryStream(Store.DIR, "*.tsv")) {
                for (Path p : ds) {
                    z.putNextEntry(new ZipEntry(p.getFileName().toString()));
                    Files.copy(p, z);
                    z.closeEntry();
                }
            }
            return target;
        }

        static Path backupDefault(String prefix) throws IOException {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            return backup(Store.BACKUPS.resolve(prefix + ts + ".zip"));
        }

        static void autoBackup() {
            try {
                backupDefault("backup_");
                List<Path> old;
                try (java.util.stream.Stream<Path> st = Files.list(Store.BACKUPS)) {
                    old = st.filter(p -> p.getFileName().toString().startsWith("backup_"))
                            .sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                }
                for (int i = 10; i < old.size(); i++) Files.deleteIfExists(old.get(i));   // keep newest 10
            } catch (Exception ignored) { }
        }

        static void restore(Path zip) throws IOException {
            Map<String, byte[]> files = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (!e.getName().matches("[a-z_]+\\.tsv")) continue;      // blocks zip-slip / junk
                    files.put(e.getName(), z.readAllBytes());
                }
            }
            if (!files.containsKey("users.tsv")) throw new IOException("This is not a valid clearance backup.");
            backupDefault("pre_restore_");                                    // safety copy of current data
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Store.DIR, "*.tsv")) {
                for (Path p : ds) Files.delete(p);
            }
            for (Map.Entry<String, byte[]> en : files.entrySet()) Files.write(Store.DIR.resolve(en.getKey()), en.getValue());
            Data.load();
        }
    }

    // =====================================================================
    //  UI HELPERS
    // =====================================================================
    interface Refreshable { void refresh(); }

    /** SIGNATORY and INSTRUCTOR both review students for one department. */
    static boolean isReviewer(String role) { return role.equals("SIGNATORY") || role.equals("INSTRUCTOR"); }

    static final Color NAVY = new Color(20, 54, 99);

    static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    static JTable table(DefaultTableModel m) {
        JTable t = new JTable(m);
        t.setRowHeight(26);
        t.setAutoCreateRowSorter(true);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
        t.setDefaultRenderer(Object.class, new StatusRenderer());
        return t;
    }

    static void narrowFirstColumn(JTable t) {
        if (t.getColumnCount() > 0) {
            t.getColumnModel().getColumn(0).setMaxWidth(70);
            t.getColumnModel().getColumn(0).setPreferredWidth(50);
        }
    }

    static JButton btn(String text, ActionListener a) {
        JButton b = new JButton(text);
        b.addActionListener(a);
        return b;
    }

    static JPanel toolbar(Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        for (Component c : comps) p.add(c);
        return p;
    }

    static void info(Component p, String m) { JOptionPane.showMessageDialog(p, m, "Clearance System", JOptionPane.INFORMATION_MESSAGE); }
    static void error(Component p, String m) { JOptionPane.showMessageDialog(p, m, "Error", JOptionPane.ERROR_MESSAGE); }
    static boolean confirm(Component p, String m) {
        return JOptionPane.showConfirmDialog(p, m, "Please confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    static boolean form(Component parent, String title, String[] labels, JComponent[] fields) {
        JPanel pnl = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labels.length; i++) {
            g.gridx = 0; g.gridy = i; g.weightx = 0; pnl.add(new JLabel(labels[i]), g);
            g.gridx = 1; g.weightx = 1; pnl.add(fields[i], g);
        }
        return JOptionPane.showConfirmDialog(parent, pnl, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Returns the ID (column 0) of the selected row, or -1. */
    static int selectedId(JTable t) {
        int r = t.getSelectedRow();
        return r < 0 ? -1 : (Integer) t.getValueAt(r, 0);
    }

    static void onType(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }

    /** Colours status cells: green / yellow / red / blue. */
    static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            String s = v == null ? "" : v.toString();
            boolean status = s.equals("APPROVED") || s.equals("CLEARED") || s.equals("REJECTED")
                    || s.equals("PENDING") || s.equals("IN_PROGRESS") || s.equals("READY");
            setHorizontalAlignment(status ? CENTER : LEFT);
            if (!sel) {
                comp.setBackground(t.getBackground());
                comp.setForeground(t.getForeground());
                switch (s) {
                    case "APPROVED": case "CLEARED":
                        comp.setBackground(new Color(198, 239, 206)); comp.setForeground(new Color(0, 97, 0)); break;
                    case "REJECTED":
                        comp.setBackground(new Color(255, 199, 206)); comp.setForeground(new Color(156, 0, 6)); break;
                    case "PENDING": case "IN_PROGRESS":
                        comp.setBackground(new Color(255, 235, 156)); comp.setForeground(new Color(120, 80, 0)); break;
                    case "READY":
                        comp.setBackground(new Color(189, 215, 238)); comp.setForeground(new Color(0, 50, 110)); break;
                    default: break;
                }
            }
            return comp;
        }
    }

    // ---- printable clearance slip ----
    static String slipText(Clearance c) {
        Student s = Data.student(c.studentId);
        StringBuilder b = new StringBuilder();
        String bar = "=".repeat(76);
        b.append(bar).append('\n');
        b.append(center(c.issued() ? "STUDENT CLEARANCE FORM" : "CLEARANCE STATUS REPORT (NOT VALID FOR RELEASE)", 76)).append('\n');
        b.append(bar).append("\n\n");
        b.append(String.format("Control No. : %s%n", c.issued() ? c.controlNo : "-- not yet issued --"));
        b.append(String.format("Term        : %s%n", c.term));
        b.append(String.format("Student No. : %s%n", s.no));
        b.append(String.format("Name        : %s %s%n", s.first, s.last));
        b.append(String.format("Course/Year : %s - %d  Section %s%n%n", s.course, s.year, s.section));
        b.append(String.format("%-26s %-10s %-22s %s%n", "DEPARTMENT", "STATUS", "SIGNED BY", "DATE"));
        b.append("-".repeat(76)).append('\n');
        for (Dept d : Data.depts) {
            Item i = c.item(d.id);
            if (i == null) continue;
            b.append(String.format("%-26s %-10s %-22s %s%n", d.name, i.status, i.signedBy, i.date));
            if (!i.remarks.isEmpty()) b.append("   Remarks: ").append(i.remarks.replace('\n', ' ')).append('\n');
        }
        b.append("-".repeat(76)).append("\n\n");
        if (c.issued()) {
            b.append("Date issued: ").append(c.dateIssued).append("\n\n\n");
            b.append("______________________________\n");
            b.append("  Registrar / Clearance Officer\n");
        } else {
            b.append("Overall status: ").append(Svc.overall(c)).append('\n');
        }
        return b.toString();
    }

    static String center(String s, int w) {
        int pad = Math.max(0, (w - s.length()) / 2);
        return " ".repeat(pad) + s;
    }

    static void showSlip(Component parent, Clearance c) {
        Window w = SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(w, "Clearance Slip", Dialog.ModalityType.APPLICATION_MODAL);
        JTextArea area = new JTextArea(slipText(c));
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setMargin(new Insets(10, 10, 10, 10));
        JButton print = btn("Print", e -> {
            try { area.print(); } catch (Exception ex) { error(d, "Printing failed: " + ex.getMessage()); }
        });
        JButton close = btn("Close", e -> d.dispose());
        d.add(new JScrollPane(area), BorderLayout.CENTER);
        d.add(toolbar(print, close), BorderLayout.SOUTH);
        d.setSize(700, 560);
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }

    // =====================================================================
    //  LOGIN
    // =====================================================================
    static class LoginFrame extends JFrame {
        LoginFrame() {
            super("Student Clearance System - Login");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            JPanel header = new JPanel(new GridLayout(2, 1));
            header.setBackground(NAVY);
            header.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            JLabel t1 = new JLabel("Student Clearance Management System");
            t1.setForeground(Color.WHITE); t1.setFont(t1.getFont().deriveFont(Font.BOLD, 18f));
            JLabel t2 = new JLabel("Offline edition - no internet or database required");
            t2.setForeground(new Color(200, 215, 235));
            header.add(t1); header.add(t2);

            JTextField user = new JTextField(18);
            JPasswordField pass = new JPasswordField(18);
            JLabel err = new JLabel(" ");
            err.setForeground(new Color(180, 0, 0));
            JButton go = new JButton("Login");

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(5, 5, 5, 5); g.fill = GridBagConstraints.HORIZONTAL;
            g.gridx = 0; g.gridy = 0; form.add(new JLabel("Username"), g);
            g.gridx = 1; form.add(user, g);
            g.gridx = 0; g.gridy = 1; form.add(new JLabel("Password"), g);
            g.gridx = 1; form.add(pass, g);
            g.gridx = 1; g.gridy = 2; form.add(go, g);
            g.gridx = 0; g.gridy = 3; g.gridwidth = 2; form.add(err, g);
            g.gridy = 4;
            JLabel hint = new JLabel("<html><small>Students: use your Student No. as username and password.</small></html>");
            hint.setForeground(Color.GRAY);
            form.add(hint, g);

            Runnable doLogin = () -> {
                User u = Svc.login(user.getText().trim(), new String(pass.getPassword()));
                if (u == null) { err.setText("Invalid username or password."); pass.setText(""); return; }
                Svc.current = u;
                Svc.log("Login");
                dispose();
                new MainFrame(u).setVisible(true);
            };
            go.addActionListener(e -> doLogin.run());
            getRootPane().setDefaultButton(go);

            add(header, BorderLayout.NORTH);
            add(form, BorderLayout.CENTER);
            pack();
            setResizable(false);
            setLocationRelativeTo(null);
        }
    }

    // =====================================================================
    //  MAIN FRAME (tabs depend on role)
    // =====================================================================
    static class MainFrame extends JFrame {
        final User user;

        MainFrame(User u) {
            super("Offline Student Clearance Management System");
            this.user = u;
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { exit(); }
            });

            JTabbedPane tabs = new JTabbedPane();
            switch (u.role) {
                case "ADMIN":
                    tabs.addTab("Users", new UsersPanel());
                    tabs.addTab("Departments", new DepartmentsPanel());
                    tabs.addTab("Backup / Restore", new BackupPanel(this));
                    tabs.addTab("Audit Log", new AuditPanel());
                    break;
                case "REGISTRAR":
                    tabs.addTab("Students", new StudentsPanel());
                    tabs.addTab("Clearance Monitor", new ClearancePanel());
                    tabs.addTab("Audit Log", new AuditPanel());
                    break;
                case "SIGNATORY": case "INSTRUCTOR":
                    tabs.addTab("Review Students", new ReviewPanel(u));
                    break;
                default:
                    tabs.addTab("My Clearance", new MyClearancePanel(u));
            }
            tabs.addChangeListener(e -> {
                Component c = tabs.getSelectedComponent();
                if (c instanceof Refreshable) ((Refreshable) c).refresh();
            });

            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(NAVY);
            header.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            JLabel title = new JLabel("Student Clearance Management System");
            title.setForeground(Color.WHITE); title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
            JLabel who = new JLabel(u.name + "  |  " + u.role);
            who.setForeground(new Color(200, 215, 235));
            header.add(title, BorderLayout.WEST);
            header.add(who, BorderLayout.EAST);

            JMenuBar mb = new JMenuBar();
            JMenu acct = new JMenu("Account");
            JMenuItem pw = new JMenuItem("Change Password...");
            pw.addActionListener(e -> changePassword());
            JMenuItem lo = new JMenuItem("Logout");
            lo.addActionListener(e -> logout());
            JMenuItem ex = new JMenuItem("Exit");
            ex.addActionListener(e -> exit());
            acct.add(pw); acct.add(lo); acct.addSeparator(); acct.add(ex);
            mb.add(acct);
            setJMenuBar(mb);

            add(header, BorderLayout.NORTH);
            add(tabs, BorderLayout.CENTER);
            setSize(1150, 660);
            setLocationRelativeTo(null);
        }

        void changePassword() {
            JPasswordField o = new JPasswordField(16), n = new JPasswordField(16), c = new JPasswordField(16);
            if (!form(this, "Change Password", new String[]{"Current password", "New password", "Confirm new"},
                    new JComponent[]{o, n, c})) return;
            if (!Svc.verify(new String(o.getPassword()), user.hash)) { error(this, "Current password is wrong."); return; }
            String np = new String(n.getPassword());
            if (np.length() < 6) { error(this, "New password must be at least 6 characters."); return; }
            if (!np.equals(new String(c.getPassword()))) { error(this, "Passwords do not match."); return; }
            user.hash = Svc.hash(np);
            Data.saveUsers();
            Svc.log("Changed own password");
            info(this, "Password changed.");
        }

        void logout() {
            if (!user.role.equals("STUDENT")) Svc.autoBackup();
            Svc.log("Logout");
            Svc.current = null;
            dispose();
            new LoginFrame().setVisible(true);
        }

        void exit() {
            if (!user.role.equals("STUDENT")) Svc.autoBackup();
            Svc.log("Exit");
            dispose();
            System.exit(0);
        }
    }

    // =====================================================================
    //  ADMIN: USERS
    // =====================================================================
    static class UsersPanel extends JPanel implements Refreshable {
        final DefaultTableModel m = model("ID", "Username", "Name", "Role", "Department / Student");
        final JTable t = table(m);

        UsersPanel() {
            setLayout(new BorderLayout());
            add(toolbar(btn("Add User", e -> add()), btn("Reset Password", e -> reset()),
                    btn("Delete", e -> delete()), btn("Refresh", e -> refresh())), BorderLayout.NORTH);
            add(new JScrollPane(t), BorderLayout.CENTER);
            refresh();
        }

        public void refresh() {
            m.setRowCount(0);
            for (User u : Data.users) {
                String ref = "";
                if (isReviewer(u.role)) { Dept d = Data.dept(u.refId); ref = d == null ? "?" : d.name; }
                if (u.role.equals("STUDENT")) ref = u.username;
                m.addRow(new Object[]{u.id, u.username, u.name, u.role, ref});
            }
            narrowFirstColumn(t);
        }

        void add() {
            JComboBox<String> role = new JComboBox<>(new String[]{"ADMIN", "REGISTRAR", "SIGNATORY", "INSTRUCTOR", "STUDENT"});
            JTextField un = new JTextField(18), nm = new JTextField(18);
            JComboBox<Dept> dp = new JComboBox<>(Data.depts.toArray(new Dept[0]));
            JPasswordField pw = new JPasswordField(18);
            if (!form(this, "Add User",
                    new String[]{"Role", "Username (Student No. for students)", "Full name (not needed for students)",
                            "Department (signatory / instructor)", "Password"},
                    new JComponent[]{role, un, nm, dp, pw})) return;

            String r = (String) role.getSelectedItem();
            final String typed = un.getText().trim();
            if (typed.isEmpty()) { error(this, "Username is required."); return; }
            if (new String(pw.getPassword()).length() < 6) { error(this, "Password must be at least 6 characters."); return; }
            if (Data.users.stream().anyMatch(x -> x.username.equalsIgnoreCase(typed))) { error(this, "Username already exists."); return; }

            String username = typed, name = nm.getText().trim();
            int ref = 0;
            if (r.equals("STUDENT")) {
                Student s = Data.students.stream().filter(x -> x.no.equalsIgnoreCase(typed)).findFirst().orElse(null);
                if (s == null) { error(this, "No student has that Student No. Add the student first (Registrar > Students)."); return; }
                ref = s.id; name = s.full(); username = s.no;
            } else {
                if (name.isEmpty()) { error(this, "Full name is required."); return; }
                if (isReviewer(r)) {
                    if (dp.getSelectedItem() == null) { error(this, "Choose a department."); return; }
                    ref = ((Dept) dp.getSelectedItem()).id;
                }
            }
            int id = Svc.nextId(Data.users.stream().map(x -> x.id).collect(Collectors.toList()));
            Data.users.add(new User(id, username, Svc.hash(new String(pw.getPassword())), r, ref, name));
            Data.saveUsers();
            Svc.log("Added " + r + " user " + username);
            refresh();
        }

        User selected() {
            int id = selectedId(t);
            if (id < 0) { info(this, "Select a user first."); return null; }
            return Data.users.stream().filter(x -> x.id == id).findFirst().orElse(null);
        }

        void reset() {
            User u = selected();
            if (u == null) return;
            if (u.role.equals("STUDENT")) {
                if (!confirm(this, "Reset this student's password back to their Student No.?")) return;
                Data.users.remove(u);
                Data.saveUsers();
                Svc.log("Reset student password " + u.username);
                refresh();
                return;
            }
            JPasswordField pw = new JPasswordField(16);
            if (!form(this, "Reset password for " + u.username, new String[]{"New password"}, new JComponent[]{pw})) return;
            if (new String(pw.getPassword()).length() < 6) { error(this, "Password must be at least 6 characters."); return; }
            u.hash = Svc.hash(new String(pw.getPassword()));
            Data.saveUsers();
            Svc.log("Reset password for " + u.username);
            info(this, "Password updated.");
        }

        void delete() {
            User u = selected();
            if (u == null) return;
            if (u == Svc.current) { error(this, "You cannot delete the account you are logged in with."); return; }
            if (!confirm(this, "Delete user '" + u.username + "'?")) return;
            Data.users.remove(u);
            Data.saveUsers();
            Svc.log("Deleted user " + u.username);
            refresh();
        }
    }

    // =====================================================================
    //  ADMIN: DEPARTMENTS (signing offices)
    // =====================================================================
    static class DepartmentsPanel extends JPanel implements Refreshable {
        final DefaultTableModel m = model("ID", "Department", "Sign Order");
        final JTable t = table(m);

        DepartmentsPanel() {
            setLayout(new BorderLayout());
            add(toolbar(btn("Add", e -> edit(null)),
                    btn("Edit", e -> { Dept x = selected(); if (x == null) info(this, "Select a department first."); else edit(x); }),
                    btn("Delete", e -> delete())), BorderLayout.NORTH);
            add(new JScrollPane(t), BorderLayout.CENTER);
            add(new JLabel("  New departments apply to clearances opened afterwards."), BorderLayout.SOUTH);
            refresh();
        }

        public void refresh() {
            m.setRowCount(0);
            for (Dept d : Data.depts) m.addRow(new Object[]{d.id, d.name, d.order});
            narrowFirstColumn(t);
        }

        Dept selected() {
            int id = selectedId(t);
            return id < 0 ? null : Data.dept(id);
        }

        void edit(Dept d) {
            boolean isNew = d == null;
            JTextField name = new JTextField(isNew ? "" : d.name, 18);
            JSpinner order = new JSpinner(new SpinnerNumberModel(isNew ? Data.depts.size() + 1 : d.order, 1, 99, 1));
            if (!form(this, isNew ? "Add Department" : "Edit Department", new String[]{"Name", "Sign order"},
                    new JComponent[]{name, order})) return;
            if (name.getText().trim().isEmpty()) { error(this, "Name is required."); return; }
            if (isNew) {
                d = new Dept();
                d.id = Svc.nextId(Data.depts.stream().map(x -> x.id).collect(Collectors.toList()));
                Data.depts.add(d);
            }
            d.name = name.getText().trim();
            d.order = (Integer) order.getValue();
            Data.sortDepts();
            Data.saveDepts();
            Svc.log((isNew ? "Added" : "Edited") + " department " + d.name);
            refresh();
        }

        void delete() {
            Dept d = selected();
            if (d == null) { info(this, "Select a department first."); return; }
            boolean used = Data.clearances.stream().anyMatch(c -> c.item(d.id) != null)
                    || Data.users.stream().anyMatch(u -> isReviewer(u.role) && u.refId == d.id);
            if (used) { error(this, "This department is used by existing clearances or staff accounts and cannot be deleted."); return; }
            if (!confirm(this, "Delete department '" + d.name + "'?")) return;
            Data.depts.remove(d);
            Data.saveDepts();
            Svc.log("Deleted department " + d.name);
            refresh();
        }
    }

    // =====================================================================
    //  ADMIN: BACKUP / RESTORE
    // =====================================================================
    static class BackupPanel extends JPanel implements Refreshable {
        final DefaultTableModel m = model("File", "Size (KB)", "Modified");
        final JTable t = table(m);
        final MainFrame frame;

        BackupPanel(MainFrame frame) {
            this.frame = frame;
            setLayout(new BorderLayout());
            add(toolbar(btn("Backup Now", e -> now()), btn("Backup To (USB / folder)...", e -> to()),
                    btn("Restore From File...", e -> restore())), BorderLayout.NORTH);
            add(new JScrollPane(t), BorderLayout.CENTER);
            add(new JLabel("  Automatic backups are made when staff log out or exit (newest 10 kept in ./backups)."), BorderLayout.SOUTH);
            refresh();
        }

        public void refresh() {
            m.setRowCount(0);
            try {
                if (!Files.exists(Store.BACKUPS)) return;
                try (java.util.stream.Stream<Path> st = Files.list(Store.BACKUPS)) {
                    for (Path p : st.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
                        m.addRow(new Object[]{p.getFileName().toString(), Files.size(p) / 1024 + 1,
                                LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(),
                                        java.time.ZoneId.systemDefault()).format(Svc.TS)});
                }
            } catch (IOException ignored) { }
        }

        void now() {
            try { Path p = Svc.backupDefault("backup_"); Svc.log("Manual backup " + p.getFileName()); info(this, "Backup saved:\n" + p.toAbsolutePath()); refresh(); }
            catch (IOException e) { error(this, "Backup failed: " + e.getMessage()); }
        }

        void to() {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("clearance_backup.zip"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try { Svc.backup(fc.getSelectedFile().toPath()); Svc.log("Backup to " + fc.getSelectedFile()); info(this, "Backup saved."); }
            catch (IOException e) { error(this, "Backup failed: " + e.getMessage()); }
        }

        void restore() {
            JFileChooser fc = new JFileChooser(Store.BACKUPS.toFile());
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            if (!confirm(this, "Restoring replaces ALL current data (a safety copy is made first).\nContinue?")) return;
            try {
                Svc.restore(fc.getSelectedFile().toPath());
                info(this, "Restore complete. Please log in again.");
                Svc.current = null;
                frame.dispose();
                new LoginFrame().setVisible(true);
            } catch (IOException e) { error(this, "Restore failed: " + e.getMessage()); }
        }
    }

    // =====================================================================
    //  AUDIT LOG
    // =====================================================================
    static class AuditPanel extends JPanel implements Refreshable {
        final DefaultTableModel m = model("Time", "User", "Action");
        final JTable t = table(m);
        final JTextField search = new JTextField(18);

        AuditPanel() {
            setLayout(new BorderLayout());
            add(toolbar(new JLabel("Search:"), search, btn("Refresh", e -> refresh())), BorderLayout.NORTH);
            add(new JScrollPane(t), BorderLayout.CENTER);
            onType(search, this::refresh);
            refresh();
        }

        public void refresh() {
            m.setRowCount(0);
            String q = search.getText().trim().toLowerCase();
            for (int i = Data.audit.size() - 1; i >= 0; i--) {
                String[] r = Data.audit.get(i);
                if (!q.isEmpty() && !(r[1] + " " + r[2]).toLowerCase().contains(q)) continue;
                m.addRow(new Object[]{r[0], r[1], r[2]});
            }
            t.getColumnModel().getColumn(0).setPreferredWidth(150);
            t.getColumnModel().getColumn(0).setMaxWidth(180);
            t.getColumnModel().getColumn(1).setMaxWidth(160);
        }
    }

    // =====================================================================
    //  REGISTRAR: STUDENT RECORDS
    // =====================================================================
    static class StudentsPanel extends JPanel implements Refreshable {
        final DefaultTableModel m = model("ID", "Student No", "Name", "Course", "Year", "Section", "Status");
        final JTable t = table(m);
        final JTextField search = new JTextField(16);

        StudentsPanel() {
            setLayout(new BorderLayout());
            add(toolbar(new JLabel("Search:"), search, btn("Add", e -> edit(null)),
                    btn("Edit", e -> { Student x = selected(); if (x != null) edit(x); }), btn("Delete", e -> delete()),
                    btn("Import CSV...", e -> importCsv())), BorderLayout.NORTH);
            add(new JScrollPane(t), BorderLayout.CENTER);
            add(new JLabel("  CSV columns: studentNo,firstName,lastName,course,yearLevel,section"), BorderLayout.SOUTH);
            onType(search, this::refresh);
            refresh();
        }

        public void refresh() {
            m.setRowCount(0);
            String q = search.getText().trim().toLowerCase();
            for (Student s : Data.students) {
                if (!q.isEmpty() && !(s.no + " " + s.first + " " + s.last + " " + s.course).toLowerCase().contains(q)) continue;
                m.addRow(new Object[]{s.id, s.no, s.full(), s.course, s.year, s.section, s.active ? "ACTIVE" : "INACTIVE"});
            }
            narrowFirstColumn(t);
        }

        Student selected() {
            int id = selectedId(t);
            if (id < 0) { info(this, "Select a student first."); return null; }
            return Data.student(id);
        }

        void edit(Student s) {
            boolean isNew = (s == null);
            JTextField no = new JTextField(isNew ? "" : s.no, 16), fn = new JTextField(isNew ? "" : s.first, 16),
                    ln = new JTextField(isNew ? "" : s.last, 16), co = new JTextField(isNew ? "" : s.course, 16),
                    se = new JTextField(isNew ? "" : s.section, 16);
            JSpinner yr = new JSpinner(new SpinnerNumberModel(isNew ? 1 : s.year, 1, 8, 1));
            JCheckBox act = new JCheckBox("Active", isNew || s.active);
            if (!form(this, isNew ? "Add Student" : "Edit Student",
                    new String[]{"Student No.", "First name", "Last name", "Course", "Year level", "Section", ""},
                    new JComponent[]{no, fn, ln, co, yr, se, act})) return;
            String n = no.getText().trim();
            if (n.isEmpty() || fn.getText().trim().isEmpty() || ln.getText().trim().isEmpty()) {
                error(this, "Student No., first name and last name are required."); return;
            }
            final Student self = s;
            if (Data.students.stream().anyMatch(x -> x != self && x.no.equalsIgnoreCase(n))) {
                error(this, "That Student No. already exists."); return;
            }
            if (isNew) {
                s = new Student();
                s.id = Svc.nextId(Data.students.stream().map(x -> x.id).collect(Collectors.toList()));
                Data.students.add(s);
            }
            s.no = n; s.first = fn.getText().trim(); s.last = ln.getText().trim();
            s.course = co.getText().trim(); s.year = (Integer) yr.getValue();
            s.section = se.getText().trim(); s.active = act.isSelected();
            final Student fs = s;
            for (User u : Data.users) if (u.role.equals("STUDENT") && u.refId == fs.id) { u.username = fs.no; u.name = fs.full(); }
            Data.saveStudents(); Data.saveUsers();
            Svc.log((isNew ? "Added" : "Edited") + " student " + s.no);
            refresh();
        }

        void delete() {
            Student s = selected();
            if (s == null) return;
            if (!confirm(this, "Delete " + s.full() + " and all of their clearance records?")) return;
            Data.students.remove(s);
            Data.clearances.removeIf(c -> c.studentId == s.id);
            Data.users.removeIf(u -> u.role.equals("STUDENT") && u.refId == s.id);
            Data.saveStudents(); Data.saveClearances(); Data.saveUsers();
            Svc.log("Deleted student " + s.no);
            refresh();
        }

        void importCsv() {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try {
                int[] r = Svc.importCsv(fc.getSelectedFile().toPath());
                info(this, r[0] + " student(s) imported, " + r[1] + " skipped (duplicate or incomplete).");
                refresh();
            } catch (IOException e) { error(this, "Import failed: " + e.getMessage()); }
        }
    }

    // =====================================================================
    //  REGISTRAR: CLEARANCE MONITOR
    // =====================================================================
    static class ClearancePanel extends JPanel implements Refreshable {
        final JComboBox<String> term = new JComboBox<>();
        final JComboBox<String> filter = new JComboBox<>(new String[]{"ALL", "IN_PROGRESS", "READY", "CLEARED"});
        final JTextField search = new JTextField(12);
        final JTable t = table(model("ID"));
        final JLabel summary = new JLabel(" ");
        boolean loading = false;

        ClearancePanel() {
            setLayout(new BorderLayout());
            term.setEditable(true);
            term.setPrototypeDisplayValue("SY 0000-0000 1st Semester xx");
            add(toolbar(new JLabel("Term:"), term, btn("Open Clearance", e -> open()),
                            new JLabel("Status:"), filter, new JLabel("Search:"), search),
                    BorderLayout.NORTH);
            JPanel south = new JPanel(new BorderLayout());
            south.add(toolbar(btn("Issue Final Clearance", e -> issue()), btn("View / Print Slip", e -> slip()),
                    btn("Export CSV...", e -> export()), btn("Refresh", e -> refresh())), BorderLayout.WEST);
            south.add(summary, BorderLayout.EAST);
            south.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
            add(new JScrollPane(t), BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);
            term.addActionListener(e -> { if (!loading) fill(); });
            filter.addActionListener(e -> fill());
            onType(search, this::fill);
            refresh();
        }

        String term() {
            Object o = term.getEditor().getItem();
            return o == null ? "" : o.toString().trim();
        }

        public void refresh() {
            loading = true;
            String keep = term();
            TreeSet<String> terms = new TreeSet<>(Comparator.reverseOrder());
            for (Clearance c : Data.clearances) terms.add(c.term);
            term.removeAllItems();
            for (String s : terms) term.addItem(s);
            if (keep.isEmpty()) {
                int y = LocalDate.now().getYear();
                keep = terms.isEmpty() ? "SY " + y + "-" + (y + 1) + " 1st Semester" : terms.first();
            }
            term.setSelectedItem(keep);
            loading = false;
            fill();
        }

        void fill() {
            String tm = term(), f = (String) filter.getSelectedItem(), q = search.getText().trim().toLowerCase();
            List<String> cols = new ArrayList<>(Arrays.asList("ID", "Student No", "Name", "Course"));
            for (Dept d : Data.depts) cols.add(d.name);
            cols.add("Overall"); cols.add("Control No");
            DefaultTableModel m = model(cols.toArray(new String[0]));
            int total = 0, cleared = 0, ready = 0;
            for (Clearance c : Data.clearances) {
                if (!c.term.equals(tm)) continue;
                Student s = Data.student(c.studentId);
                if (s == null) continue;
                String ov = Svc.overall(c);
                total++;
                if (ov.equals("CLEARED")) cleared++;
                if (ov.equals("READY")) ready++;
                if (!f.equals("ALL") && !ov.equals(f)) continue;
                if (!q.isEmpty() && !(s.full() + " " + s.no).toLowerCase().contains(q)) continue;
                List<Object> row = new ArrayList<>(Arrays.asList(c.id, s.no, s.full(), s.course));
                for (Dept d : Data.depts) { Item i = c.item(d.id); row.add(i == null ? "-" : i.status); }
                row.add(ov); row.add(c.controlNo);
                m.addRow(row.toArray());
            }
            t.setModel(m);
            narrowFirstColumn(t);
            summary.setText(total + " student(s) this term  |  " + ready + " ready to issue  |  " + cleared + " cleared");
        }

        Clearance selected() {
            int id = selectedId(t);
            if (id < 0) { info(this, "Select a student row first."); return null; }
            return Data.clearance(id);
        }

        void open() {
            String tm = term();
            if (tm.isEmpty()) { error(this, "Enter a term name, e.g. SY 2026-2027 1st Semester."); return; }
            if (Data.depts.isEmpty()) { error(this, "Add at least one department first."); return; }
            if (!confirm(this, "Open clearance '" + tm + "' for all active students without one?")) return;
            int n = Svc.openClearance(tm);
            info(this, n + " clearance(s) created.");
            refresh();
        }

        void issue() {
            Clearance c = selected();
            if (c == null) return;
            if (c.issued()) { info(this, "Already issued: " + c.controlNo); return; }
            if (!c.allApproved()) { error(this, "All departments must approve before the final clearance can be issued."); return; }
            String no = Svc.issue(c);
            info(this, "Final clearance issued.\nControl No.: " + no);
            fill();
            showSlip(this, c);
        }

        void slip() {
            Clearance c = selected();
            if (c != null) showSlip(this, c);
        }

        void export() {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("clearance_report.csv"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try (BufferedWriter w = Files.newBufferedWriter(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8)) {
                for (int c = 0; c < t.getColumnCount(); c++) w.write((c > 0 ? "," : "") + csv(t.getColumnName(c)));
                w.newLine();
                for (int r = 0; r < t.getRowCount(); r++) {
                    for (int c = 0; c < t.getColumnCount(); c++) w.write((c > 0 ? "," : "") + csv(String.valueOf(t.getValueAt(r, c))));
                    w.newLine();
                }
                Svc.log("Exported clearance report CSV");
                info(this, "Report exported.");
            } catch (IOException e) { error(this, "Export failed: " + e.getMessage()); }
        }

        String csv(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
    }

    // =====================================================================
    //  SIGNATORY / INSTRUCTOR: REVIEW STUDENTS FOR ONE DEPARTMENT
    // =====================================================================
    static class ReviewPanel extends JPanel implements Refreshable {
        final DefaultTableModel m = model("ID", "Student No", "Name", "Course", "Term", "Status", "Remarks", "Date");
        final JTable t = table(m);
        final JComboBox<String> filter = new JComboBox<>(new String[]{"PENDING", "REJECTED", "APPROVED", "ALL"});
        final JTextField search = new JTextField(14);
        final Dept dept;

        ReviewPanel(User u) {
            dept = Data.dept(u.refId);
            setLayout(new BorderLayout());
            t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            JLabel head = new JLabel(dept == null ? "No department is linked to this account." : "Department: " + dept.name);
            head.setFont(head.getFont().deriveFont(Font.BOLD, 14f));
            add(toolbar(head, new JLabel("   Show:"), filter, new JLabel("Search:"), search), BorderLayout.NORTH);
            add(new JScrollPane(t), BorderLayout.CENTER);
            add(toolbar(btn("Approve", e -> act("APPROVED")), btn("Reject", e -> act("REJECTED")),
                    btn("Mark Pending", e -> act("PENDING")), btn("Refresh", e -> refresh()),
                    new JLabel("  (select several rows to update them at once)")), BorderLayout.SOUTH);
            filter.addActionListener(e -> refresh());
            onType(search, this::refresh);
            refresh();
        }

        public void refresh() {
            m.setRowCount(0);
            if (dept == null) return;
            String f = (String) filter.getSelectedItem(), q = search.getText().trim().toLowerCase();
            for (Clearance c : Data.clearances) {
                Item i = c.item(dept.id);
                Student s = Data.student(c.studentId);
                if (i == null || s == null) continue;
                if (!f.equals("ALL") && !i.status.equals(f)) continue;
                if (!q.isEmpty() && !(s.full() + " " + s.no).toLowerCase().contains(q)) continue;
                m.addRow(new Object[]{c.id, s.no, s.full(), s.course, c.term, i.status, i.remarks, i.date});
            }
            narrowFirstColumn(t);
        }

        void act(String status) {
            int[] rows = t.getSelectedRows();
            if (rows.length == 0) { info(this, "Select one or more students first."); return; }
            JTextArea ta = new JTextArea(4, 30);
            ta.setLineWrap(true);
            Object[] msg = {status.equals("APPROVED") ? "Remarks (optional):" : "Remarks / requirement to settle:", new JScrollPane(ta)};
            if (JOptionPane.showConfirmDialog(this, msg, status + " " + rows.length + " student(s)",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String remarks = ta.getText().trim();
            if (status.equals("REJECTED") && remarks.isEmpty()) { error(this, "Please state the reason when rejecting."); return; }
            List<Integer> ids = new ArrayList<>();
            for (int r : rows) ids.add((Integer) t.getValueAt(r, 0));
            int done = 0, locked = 0;
            for (int id : ids) {
                Clearance c = Data.clearance(id);
                if (c == null) continue;
                if (c.issued()) { locked++; continue; }
                Svc.updateItem(c, dept.id, status, remarks);
                done++;
            }
            Data.saveClearances();
            refresh();
            if (locked > 0) info(this, done + " updated. " + locked + " skipped because the final clearance was already issued.");
        }
    }

    // =====================================================================
    //  STUDENT: MY CLEARANCE
    // =====================================================================
    static class MyClearancePanel extends JPanel implements Refreshable {
        final DefaultTableModel m = model("Department", "Status", "Remarks", "Signed By", "Date");
        final JTable t = table(m);
        final JComboBox<String> termBox = new JComboBox<>();
        final JLabel overall = new JLabel(" "), control = new JLabel(" ");
        final JButton print = btn("View / Print Slip", e -> slip());
        final User user;
        List<Clearance> mine = new ArrayList<>();

        MyClearancePanel(User u) {
            this.user = u;
            setLayout(new BorderLayout());
            add(toolbar(new JLabel("Term:"), termBox, btn("Refresh", e -> refresh())), BorderLayout.NORTH);
            add(new JScrollPane(t), BorderLayout.CENTER);
            JPanel south = new JPanel(new BorderLayout());
            JPanel lbls = new JPanel(new GridLayout(2, 1));
            lbls.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            lbls.add(overall); lbls.add(control);
            south.add(lbls, BorderLayout.CENTER);
            south.add(toolbar(print), BorderLayout.EAST);
            add(south, BorderLayout.SOUTH);
            termBox.addActionListener(e -> fill());
            refresh();
        }

        public void refresh() {
            mine = Data.clearances.stream().filter(c -> c.studentId == user.refId)
                    .sorted(Comparator.comparingInt((Clearance c) -> c.id).reversed()).collect(Collectors.toList());
            termBox.removeAllItems();
            for (Clearance c : mine) termBox.addItem(c.term);
            fill();
        }

        void fill() {
            m.setRowCount(0);
            int i = termBox.getSelectedIndex();
            if (i < 0 || i >= mine.size()) {
                overall.setText("No clearance has been opened for you yet. Please check with the Registrar.");
                control.setText(" ");
                print.setEnabled(false);
                return;
            }
            Clearance c = mine.get(i);
            for (Dept d : Data.depts) {
                Item it = c.item(d.id);
                if (it != null) m.addRow(new Object[]{d.name, it.status, it.remarks, it.signedBy, it.date});
            }
            long ok = c.items.stream().filter(x -> x.status.equals("APPROVED")).count();
            overall.setText("Overall: " + Svc.overall(c) + "   (" + ok + " of " + c.items.size() + " departments approved)");
            control.setText(c.issued() ? "Control No.: " + c.controlNo + "   Issued: " + c.dateIssued
                    : "Final clearance not yet issued. Settle any rejected or pending department listed above.");
            print.setEnabled(true);
        }

        void slip() {
            int i = termBox.getSelectedIndex();
            if (i >= 0 && i < mine.size()) showSlip(this, mine.get(i));
        }
    }

    // =====================================================================
    //  ENTRY POINT
    // =====================================================================
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        if (!Store.lock()) {
            JOptionPane.showMessageDialog(null, "The Clearance System is already running (or the data folder is locked).",
                    "Clearance System", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                Data.load();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Could not read the data files in ./data:\n" + e,
                        "Startup error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
            new LoginFrame().setVisible(true);
        });
    }
}