# Setup Guide

## Database
1. Create `attendance_db` in MySQL Workbench.
2. Run [schema.sql](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/database/schema.sql).
3. Run [seed.sql](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/database/seed.sql).

## Config Files
- [config.properties](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/config.properties): repository-safe template
- [config.local.properties](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/config.local.properties): machine-local override

The application loads both files automatically and lets `config.local.properties` override the template values.

## OTP / Gmail
1. Enable 2-Step Verification on the sender Gmail.
2. Generate a Gmail App Password.
3. Put the sender address and App Password into `config.local.properties`.

## Build and Run
```powershell
javac -cp "lib/*" src\App.java src\ValidationUtils.java -d out
java -cp "out;lib/*" App
```

Or run [run.bat](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/run.bat).

## First Verification Pass
1. Log in as admin.
2. Open the Subjects page and confirm subject records load.
3. Open Staff, Supervisors, Attendance, Reports, and Schedules.
4. Register a fresh account and verify OTP delivery.
5. Log in as staff and confirm `My Shifts` and `My Attendance`.
