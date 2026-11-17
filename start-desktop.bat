@echo off
title Clean Filing Desktop
echo ========================================================
echo   Clean Filing Desktop Server
echo ========================================================
echo   Starting local server at http://localhost:8080 ...
echo   (Press Ctrl+C to stop)
echo.

start http://localhost:8080

where python >nul 2>nul
if %errorlevel% equ 0 (
    python -m http.server 8080
    goto end
)

powershell -ExecutionPolicy Bypass -Command "Write-Host 'Starting PowerShell web server on http://localhost:8080...'; $listener = New-Object System.Net.HttpListener; $listener.Prefixes.Add('http://localhost:8080/'); $listener.Start(); Start-Process 'http://localhost:8080'; while($listener.IsListening){ $ctx = $listener.GetContext(); $path = Join-Path (Get-Location) ($ctx.Request.Url.LocalPath.TrimStart('/')); if(-not (Test-Path $path) -or (Get-Item $path).PSIsContainer){ $path = Join-Path (Get-Location) 'index.html' }; $bytes = [System.IO.File]::ReadAllBytes($path); $ctx.Response.ContentLength64 = $bytes.Length; $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length); $ctx.Response.Close() }"

:end
