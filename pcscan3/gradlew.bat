@echo off
setlocal

set "DIRNAME=%~dp0"
if "%DIRNAME%"=="" set "DIRNAME=."

for %%I in ("%DIRNAME%") do set "APP_HOME=%%~fI"
set "APP_BASE_NAME=%~n0"

set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

endlocal
