# Test Checklist

## Authentication
- Admin login works
- Supervisor login works
- Staff login works
- Logout works
- Session still works after server restart

## Registration / OTP
- Registration blocks invalid email
- Registration blocks duplicate email
- Registration blocks duplicate username
- OTP is required before account creation
- DOB computes age automatically
- DOB rejects ages below 15 and above 80

## Manager / Supervisor Modules
- Staff CRUD works
- Supervisors CRUD works
- Subjects CRUD works
- Timetable CRUD works
- Attendance save works
- QR attendance save works
- Geo-location attendance works for staff account

## Staff Modules
- Staff profile loads DOB and address
- Staff can see only staff pages in navigation
- Staff QR loads
- Staff attendance records load
- Staff schedule loads

## Reports / Dashboard
- Dashboard statistics load
- Attendance trend charts load
- Reports print correctly
- Reports CSV export works
