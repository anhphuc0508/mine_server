@echo off
cd /d "%~dp0"
echo Starting T48 Minecraft server on port 25565...
call gradlew.bat --offline --console=plain installDist
if errorlevel 1 (
    pause
    exit /b 1
)
call "%~dp0build\install\mine_server\bin\mine_server.bat"
if errorlevel 1 pause
