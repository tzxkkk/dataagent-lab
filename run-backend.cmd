@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "PROJECT_DIR=%~dp0"
set "WORK_ROOT=%PROJECT_DIR%.."
set "DRIVE_ROOT=R:"
set "BUNDLED_JAVA=%WORK_ROOT%\tools\jdk17\zulu17.68.17-ca-jdk17.0.20-win_x64"
set "BUNDLED_MAVEN=%WORK_ROOT%\tools\maven\apache-maven-3.9.11"
set "USE_BUNDLED=0"

if exist "%BUNDLED_JAVA%\bin\java.exe" if exist "%BUNDLED_MAVEN%\bin\mvn.cmd" set "USE_BUNDLED=1"

if "%USE_BUNDLED%"=="1" (
  rem The original workspace keeps tools next to the project.
  set "MAP_ROOT=%WORK_ROOT%"
) else (
  rem A GitHub clone uses Java and Maven from the user's PATH.
  where mvn >nul 2>&1
  if errorlevel 1 (
    echo Maven was not found. Install Maven 3.9+ or add mvn to PATH.
    exit /b 1
  )
  where java >nul 2>&1
  if errorlevel 1 (
    echo Java was not found. Install Java 17 or add java to PATH.
    exit /b 1
  )
  set "MAP_ROOT=%PROJECT_DIR%"
)

rem Maven can be blocked by Documents permissions; use a short temporary path.
subst %DRIVE_ROOT% "%MAP_ROOT%" >nul 2>&1
if errorlevel 1 (
  echo Could not map %DRIVE_ROOT% to:
  echo %MAP_ROOT%
  exit /b 1
)

if "%USE_BUNDLED%"=="1" (
  set "JAVA_HOME=!DRIVE_ROOT!\tools\jdk17\zulu17.68.17-ca-jdk17.0.20-win_x64"
  set "MAVEN_HOME=!DRIVE_ROOT!\tools\maven\apache-maven-3.9.11"
  set "PATH=!JAVA_HOME!\bin;!MAVEN_HOME!\bin;!PATH!"
  cd /d "!DRIVE_ROOT!\dataagent-lab\backend"
  call "!MAVEN_HOME!\bin\mvn.cmd" "-Dmaven.repo.local=!DRIVE_ROOT!\m2" spring-boot:run
) else (
  cd /d "!DRIVE_ROOT!backend"
  call mvn spring-boot:run
)
set "EXIT_CODE=%ERRORLEVEL%"
cd /d "%PROJECT_DIR%"
subst %DRIVE_ROOT% /D >nul 2>&1
exit /b %EXIT_CODE%
