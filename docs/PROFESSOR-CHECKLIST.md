# Project Checklist

## Implemented
- Login / logout with role-based access
- Password hashing for stored user credentials
- OTP verification for registration, profile updates, and password reset
- Staff management
- Supervisor management
- Work area management
- Shift schedule management
- Manual attendance
- QR attendance scanning and QR generation
- Geo-location attendance check-in
- Dashboard analytics
- Reports with print and CSV export
- Staff profile with DOB and Laguna address

## Practical Notes
- Work areas now include branch, role, level, and shift group targeting.
- Staff schedule visibility depends on work area targeting.
- Supervisor views are filtered to their own assigned work areas and schedules.
- Sessions are persisted in the database through the `sessions` table.

## Files to Review
- Backend: [App.java](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/src/App.java)
- Validation helpers: [ValidationUtils.java](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/src/ValidationUtils.java)
- Database schema: [schema.sql](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/database/schema.sql)
- Main UI pages: [dashboard.html](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/web/dashboard.html), [subjects.html](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/web/subjects.html), [attendance.html](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/web/attendance.html), [timetable.html](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/web/timetable.html), [reports.html](C:/Users/ADMIN/Downloads/AttendanceManagement-20260407T143929Z-3-001/AttendanceManagement/web/reports.html)
