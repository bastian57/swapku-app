@echo off
REM Minimal wrapper for SwapKu on Windows.
REM CI installs Gradle 8.7 directly and calls gradle.
where gradle >nul 2>&1
if %ERRORLEVEL%==0 (
  gradle %*
) else (
  echo Gradle not found. Install Gradle 8.7 or use the GitHub Actions workflow.
  exit /b 1
)
