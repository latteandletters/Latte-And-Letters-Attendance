@echo off
echo ============================================
echo  AttendEase - Attendance Management System
echo ============================================

REM Check if lib folder has any JDBC driver jar
dir /b "lib\mysql-connector*.jar" >nul 2>&1
if errorlevel 1 (
    echo ERROR: No MySQL JDBC driver found in lib\
    echo Download from: https://dev.mysql.com/downloads/connector/j/
    echo Place the .jar file in the lib\ folder and run again.
    pause
    exit /b 1
)

REM Create output directory
if not exist "out" mkdir out

REM Compile  (lib\* picks up all jars automatically)
echo Compiling...
javac -cp "lib\*" src\App.java src\ValidationUtils.java -d out
if errorlevel 1 (
    echo Compilation failed!
    pause
    exit /b 1
)

echo Compiled successfully!
echo Starting server at http://localhost:8080
echo Press Ctrl+C to stop.
echo.

REM Run
java -cp "out;lib\*" App
pause
