@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM Apache Maven Wrapper startup batch script, version 3.3.2

@IF "%__MVNW_ARG0_NAME__%"=="" (SET "BASE_DIR=%~dp0") ELSE SET "BASE_DIR=%~dp0"

@SET "MAVEN_PROJECTBASEDIR=%MAVEN_BASEDIR%"
@IF NOT "%MAVEN_PROJECTBASEDIR%"=="" GOTO endDetectBaseDir

@SET "EXEC_DIR=%CD%"
@SET "WDIR=%EXEC_DIR%"
:findBaseDir
IF EXIST "%WDIR%\.mvn" GOTO baseDirFound
cd ..
IF "%WDIR%"=="%CD%" GOTO baseDirNotFound
SET "WDIR=%CD%"
GOTO findBaseDir

:baseDirFound
SET "MAVEN_PROJECTBASEDIR=%WDIR%"
CD "%EXEC_DIR%"
GOTO endDetectBaseDir

:baseDirNotFound
SET "MAVEN_PROJECTBASEDIR=%EXEC_DIR%"
CD "%EXEC_DIR%"

:endDetectBaseDir

SET "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
SET "WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"

@IF NOT EXIST "%WRAPPER_JAR%" (
  SETLOCAL EnableDelayedExpansion
  SET "WRAPPER_URL="
  FOR /F "usebackq tokens=1,* delims==" %%A IN ("%WRAPPER_PROPERTIES%") DO (
    IF "%%A"=="wrapperUrl" SET "WRAPPER_URL=%%B"
  )
  ECHO Downloading Maven Wrapper JAR...
  powershell -Command "Invoke-WebRequest -Uri '!WRAPPER_URL!' -OutFile '%WRAPPER_JAR%'"
  IF ERRORLEVEL 1 (
    ECHO Failed to download maven-wrapper.jar
    EXIT /B 1
  )
  ENDLOCAL
)

@IF NOT "%JAVA_HOME%"=="" (
  SET "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVA_CMD=java"
)

"%JAVA_CMD%" %MAVEN_OPTS% "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
