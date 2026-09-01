@echo off
REM 独立启动后端（供计划任务调用，脱离 Claude 会话进程树）
cd /d C:\java_workspace\teacher_workspace\backend
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
call C:\apache-maven-3.8.8\bin\mvn.cmd -q spring-boot:run >> boot.log 2>&1
