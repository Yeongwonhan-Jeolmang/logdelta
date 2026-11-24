@REM SPDX-License-Identifier: GPL-3.0-or-later
@REM Maven wrapper for Windows: runs the Maven version named in .mvn\wrapper\maven-wrapper.properties,
@REM downloading it once into %USERPROFILE%\.m2\wrapper\dists. Usage is the same as mvn:
@REM
@REM   mvnw.cmd verify
@echo off
setlocal
set "MAVEN_HOME="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0.mvn\wrapper\install-maven.ps1"`) do set "MAVEN_HOME=%%i"
if not defined MAVEN_HOME (
  echo mvnw: could not install Maven 1>&2
  exit /b 1
)
"%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
