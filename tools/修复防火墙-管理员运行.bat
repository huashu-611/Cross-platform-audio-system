@echo off
chcp 65001 >nul
title AudioHub 入站放行（需要管理员）

rem ==================================================================
rem  为什么需要这一步
rem
rem  Windows 防火墙按**可执行文件的完整路径**放行入站 UDP，不是按进程名、
rem  也不是按端口。AudioHub 的音频核心是独立进程 audiohub-hub.exe，
rem  便携版每次都会解包到 %TEMP%\AudioHub\...，这条路径默认没有任何
rem  放行规则；而本机的 InboundUserNotification 是关的，所以连"是否允许"
rem  的弹框都不会出现 —— 入站包被静默丢弃。
rem
rem  现象因此极具误导性：手机上显示"发送 91000 包"（send 全部成功），
rem  电脑上 rxPackets 恒 0、界面显示对方"没响应"。看着像没联网，
rem  其实是包到了网卡却没交给这个 exe。
rem
rem  实测（同一份字节，只换路径，各跑 6~8 秒，不注入任何包）：
rem    buildudiohub-hub.exe     rxPackets +2404  sources=1（有 2 条规则）
rem    build\_diag\hub_copy\...   rxPackets +0     sources=0（0 条规则）
rem ==================================================================

net session >nul 2>&1
if errorlevel 1 (
  echo [!] 需要管理员权限，正在重新以管理员身份启动...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

set HUB=%LOCALAPPDATA%\Temp\AudioHubesourcespp.asar.unpackedinudiohub-hub.exe
if not exist "%HUB%" (
  echo [!] 还没找到便携版的解包目录：
  echo     %HUB%
  echo     请先运行一次 AudioHub-便携版.exe 再执行本脚本。
  pause
  exit /b
)

echo 正在放行：%HUB%
netsh advfirewall firewall delete rule name="AudioHub Hub" >nul 2>&1
netsh advfirewall firewall add rule name="AudioHub Hub" dir=in action=allow program="%HUB%" enable=yes profile=any protocol=udp
netsh advfirewall firewall add rule name="AudioHub Hub" dir=in action=allow program="%HUB%" enable=yes profile=any protocol=tcp

echo.
echo 完成。关闭并重新打开 AudioHub 即可。
pause
