@echo off
echo ============================================
echo  DIAGNOSIS - AttendEase
echo ============================================
echo.

echo [1] Checking Java...
java -version
if errorlevel 1 (
    echo PROBLEM: Java is NOT installed or not in PATH.
    echo Fix: Download Java from https://adoptium.net and install it.
    echo After installing, restart this cmd window and run again.
    pause
    exit /b 1
)
echo Java OK.
echo.

echo [2] Checking project folder...
echo Current directory: %CD%
echo.

echo [3] Checking lib folder...
dir lib\*.jar
echo.

echo [4] Checking if out folder exists...
if not exist "out" mkdir out
echo out folder ready.
echo.

echo [5] Compiling App.java...
javac -cp "lib\*" src\App.java -d out
if errorlevel 1 (
    echo.
    echo PROBLEM: Compilation failed. See errors above.
    pause
    exit /b 1
)
echo Compilation OK.
echo.

echo [6] Checking MySQL is running on port 3306...
netstat -an | findstr ":3306"
if errorlevel 1 (
    echo PROBLEM: MySQL is NOT running on port 3306.
    echo Fix: Start your MySQL server (e.g., via MySQL Workbench or service).
    pause
    exit /b 1
)
echo MySQL port 3306 is open.
echo.

echo [7] Starting Java server...
echo Keep this window open. Open http://localhost:8080 in your browser.
echo Press Ctrl+C to stop the server.
echo.
java -cp "out;lib\*" App
pause
