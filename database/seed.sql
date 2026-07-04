-- ============================================
-- SEED DATA - Latte & Letters sample records
-- Run AFTER schema.sql
-- ============================================
USE attendance_db;

-- Default users (login password: admin123, stored as PBKDF2 hashes)
INSERT INTO users (username, password, email, role) VALUES
('admin',    'pbkdf2$65536$m76hGTFTx66WsKROwpxGHg==$SVGUqGwyeYiIaLLilsAxcQRcb0wiS9LP5CP1/R7NiIY=', 'admin@latteletters.local',      'admin'),
('supervisor1', 'pbkdf2$65536$HNTUG91MIJBFSr7lVmMwsQ==$YTGfwqZtd6BO1hiCGzxFq+hS5nbx/bT+/Pl/JYporxo=', 'supervisor@latteletters.local', 'supervisor'),
('staff1',   'pbkdf2$65536$NUrRx3nRF6tsAppUNTHOcQ==$3YSDvDNtprR/ZEG1yl+B2mi2bVqqQMycgA+NkwJqcyw=', 'barista@latteletters.local',    'staff');

-- Sample supervisor
INSERT INTO teachers (user_id, first_name, middle_name, last_name, suffix, email, phone, province, municipality, barangay, department, subject, specialization) VALUES
(2, 'Mara', NULL, 'Santos', NULL, 'supervisor@latteletters.local', '09171234567', 'LAGUNA', 'Santa Cruz', 'Bubukal', 'Main Branch', 'Coffee Bar', 'Supervisor');

-- Sample staff
INSERT INTO students (user_id, student_id, first_name, middle_name, last_name, suffix, email, phone, province, municipality, barangay, college, course, specialization, year_level, section) VALUES
(3, 'LL-0001', 'Ana', NULL, 'Reyes', NULL, 'barista@latteletters.local', '09179876543', 'LAGUNA', 'Santa Cruz', 'Bubukal', 'Main Branch', 'Coffee Bar', 'Barista', 1, 'Opening (6am - 2pm)');

-- Sample work areas
INSERT INTO subjects (code, name, college, course, specialization, year_level, section, teacher_id, units) VALUES
('BAR-01', 'Coffee Bar', 'Main Branch', 'Coffee Bar', 'Barista', 1, 'Opening (6am - 2pm)', 1, 3),
('LIB-01', 'Library Desk', 'Main Branch', 'Library Desk', 'Reading Desk', 1, 'Opening (6am - 2pm)', 1, 3),
('INV-01', 'Pantry Inventory', 'Operations', 'Inventory and Pantry', 'Stock Clerk', 1, 'Mid Shift (10am - 6pm)', 1, 3);

-- Sample shift schedules
INSERT INTO timetable (subject_id, day_of_week, start_time, end_time, room) VALUES
(1, 'Monday', '06:00:00', '14:00:00', 'Coffee Bar Counter'),
(1, 'Wednesday', '06:00:00', '14:00:00', 'Coffee Bar Counter'),
(2, 'Tuesday', '06:00:00', '14:00:00', 'Library Desk'),
(3, 'Thursday', '10:00:00', '18:00:00', 'Pantry');

-- Sample assignment
INSERT INTO enrollments (student_id, subject_id) VALUES (1, 1), (1, 2);

-- Sample attendance
INSERT INTO attendance (student_id, subject_id, date, time_in, status, method, marked_by) VALUES
(1, 1, CURDATE(), '08:05:00', 'present', 'manual', 2);
