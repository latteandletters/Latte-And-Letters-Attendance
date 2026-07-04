-- ============================================
-- ATTENDANCE MANAGEMENT SYSTEM - DATABASE SCHEMA
-- Compatible with MySQL 8.0+
-- ============================================

CREATE DATABASE IF NOT EXISTS attendance_db;
USE attendance_db;

-- Users table (managers, supervisors, staff login)
CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(50) UNIQUE,
    role ENUM('admin', 'supervisor', 'staff') NOT NULL,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Supervisors
CREATE TABLE teachers (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    first_name VARCHAR(80),
    middle_name VARCHAR(80),
    last_name VARCHAR(80),
    suffix VARCHAR(20),
    email VARCHAR(50) NOT NULL,
    phone VARCHAR(15),
    date_of_birth DATE,
    age INT,
    province VARCHAR(60),
    municipality VARCHAR(80),
    barangay VARCHAR(100),
    department VARCHAR(120),
    subject VARCHAR(120),
    specialization VARCHAR(120),
    profile_picture MEDIUMTEXT,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMP NULL DEFAULT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Staff
CREATE TABLE students (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    student_id VARCHAR(15) UNIQUE NOT NULL,
    first_name VARCHAR(80),
    middle_name VARCHAR(80),
    last_name VARCHAR(80),
    suffix VARCHAR(20),
    email VARCHAR(50),
    phone VARCHAR(15),
    date_of_birth DATE,
    age INT,
    province VARCHAR(60),
    municipality VARCHAR(80),
    barangay VARCHAR(100),
    college VARCHAR(120),
    course VARCHAR(120),
    specialization VARCHAR(120),
    year_level INT,
    section VARCHAR(40),
    profile_picture MEDIUMTEXT,
    qr_code VARCHAR(255),
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMP NULL DEFAULT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

-- Subjects
CREATE TABLE subjects (
    id INT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) UNIQUE NOT NULL,
    name VARCHAR(30) NOT NULL,
    college VARCHAR(120),
    course VARCHAR(120),
    specialization VARCHAR(120),
    year_level INT,
    section VARCHAR(40),
    teacher_id INT,
    units INT DEFAULT 3,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMP NULL DEFAULT NULL,
    FOREIGN KEY (teacher_id) REFERENCES teachers(id) ON DELETE SET NULL
);

-- Timetable
CREATE TABLE timetable (
    id INT AUTO_INCREMENT PRIMARY KEY,
    subject_id INT NOT NULL,
    day_of_week ENUM('Monday','Tuesday','Wednesday','Thursday','Friday','Saturday') NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    room VARCHAR(20),
    FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE
);

-- Staff work area assignment
CREATE TABLE enrollments (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    subject_id INT NOT NULL,
    enrolled_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_enrollment (student_id, subject_id),
    FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE
);

-- Attendance records
CREATE TABLE attendance (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    subject_id INT NOT NULL,
    date DATE NOT NULL,
    time_in TIME,
    time_out TIME,
    status ENUM('present', 'absent', 'late', 'excused') DEFAULT 'absent',
    method ENUM('manual', 'qr', 'geo') DEFAULT 'manual',
    latitude DECIMAL(10,8),
    longitude DECIMAL(11,8),
    location_address TEXT,
    remarks TEXT,
    marked_by INT,  -- supervisor or manager user_id
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_daily_attendance (student_id, subject_id, date),
    FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE
);

-- Notifications / Alerts
CREATE TABLE notifications (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    message TEXT NOT NULL,
    details TEXT NULL,
    type ENUM('alert','info','warning') DEFAULT 'info',
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notifications_user_read (user_id, is_read, created_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- OTP Verifications
CREATE TABLE otp_verifications (
    id INT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL,
    otp VARCHAR(6) NOT NULL,
    purpose ENUM('register','profile_update','reset_password') NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_email_purpose (email, purpose)
);

-- Sessions (for login tracking)
CREATE TABLE sessions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    token VARCHAR(50) UNIQUE NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
