@echo off
REM Start backend for the scheduled task (detached from Claude session)
cd /d C:\java_workspace\teacher_workspace\backend
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
REM Local dev/E2E: allow force hatch to skip the cross-day guard; production deploy scripts never set this
set PET_DEV_HATCH=true
call C:\apache-maven-3.8.8\bin\mvn.cmd -q spring-boot:run >> boot.log 2>&1
