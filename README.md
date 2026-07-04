# Latte & Letters - Attendance Management System

Latte & Letters is a Java + MySQL attendance management system for staff, supervisors, and admins. It supports attendance tracking, shift schedules, work area management, reports, QR attendance, geo-location attendance, OTP-based account verification, and role-based dashboards.

## Core Modules
- Login / Logout with hashed passwords and DB-backed sessions
- Staff management
- Supervisor management
- Work area management
- Timetable / schedule management
- Attendance management: manual, QR, and geo-location
- Dashboard analytics
- Reports with CSV export and print view
- Profile management with OTP confirmation

## Requirements
- Java 11+
- MySQL 8.0+
- MySQL Connector/J in `lib/`

## Quick Start

### 1. Create the database
1. Open MySQL Workbench.
2. Create or open your local MySQL connection.
3. Run [schema.sql](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/database/schema.sql).
4. Run [seed.sql](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/database/seed.sql).

### 2. Configure local secrets
Repository-safe defaults live in [config.properties](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/config.properties).

Put your real local credentials in [config.local.properties](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/config.local.properties). The app loads `config.properties` first, then overrides it with `config.local.properties`.

Example:

```properties
db.host=localhost
db.port=3306
db.name=attendance_db
db.user=root
db.password=your_mysql_password_here

mail.host=smtp.gmail.com
mail.port=587
mail.enabled=true
mail.from=your_sender@gmail.com
mail.password=your_16_char_app_password
mail.from_name=Latte & Letters
```

### 3. Gmail OTP setup
For OTP delivery:
1. Turn on Google 2-Step Verification for the sender Gmail account.
2. Create a Gmail App Password.
3. Use that App Password in `config.local.properties`.

### 4. Run the app
Use [run.bat](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/run.bat) or:

```powershell
javac -cp "lib/*" src\App.java src\ValidationUtils.java -d out
java -cp "out;lib/*" App
```

Open [http://localhost:8080](http://localhost:8080).

## Default Seed Accounts
- `admin / admin123`
- `supervisor1 / admin123`
- `staff1 / admin123`

Legacy plaintext seeded passwords are automatically migrated to hashed storage on startup.

## Notes About Current Behavior
- Staff schedules and work area lists are filtered to the staff member's assigned branch, role, level, and shift group when targeting is configured.
- Supervisor schedules and work area lists are filtered to the logged-in supervisor.
- Sessions are stored in the database, so restart-related logouts are reduced.
- OTP-protected flows require working mail configuration.

## Supporting Docs
- [Professor Checklist](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/docs/PROFESSOR-CHECKLIST.md)
- [Setup Guide](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/docs/SETUP-GUIDE.md)
- [Test Checklist](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/docs/TEST-CHECKLIST.md)
