@rem Gradle startup script for Windows
@rem Run `gradle wrapper --gradle-version 8.9` to regenerate the full wrapper.

@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables with windows NT shell
setlocal

set DIRNAME=%~dp0
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set JAVA_EXE=java.exe
set GRADLE_WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" -classpath "%GRADLE_WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
