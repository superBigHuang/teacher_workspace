@echo off
REM 独立启动前端 vite dev（供计划任务调用，脱离 Claude 会话进程树）
cd /d C:\java_workspace\teacher_workspace\frontend
call npm run dev >> dev.log 2>&1
