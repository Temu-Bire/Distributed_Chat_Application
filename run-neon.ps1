# run-neon.ps1 - Start ChatApp with Neon PostgreSQL on Port 8090
$ErrorActionPreference = "Stop"

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " Starting Distributed ChatApp with Neon Database" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (Test-Path .env) {
    Write-Host "Loading configuration from .env..." -ForegroundColor Yellow
    Get-Content .env | Where-Object { $_ -match '^[^#\s].*=.+' } | ForEach-Object {
        $key, $val = $_.Split('=', 2)
        [System.Environment]::SetEnvironmentVariable($key.Trim(), $val.Trim(), 'Process')
    }
} else {
    Write-Host "Setting default Neon environment variables..." -ForegroundColor Yellow
    $env:PORT = "8090"
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://ep-red-dream-a72s1yud-pooler.ap-southeast-2.aws.neon.tech/neondb?sslmode=require"
    $env:SPRING_DATASOURCE_USERNAME = "neondb_owner"
    $env:SPRING_DATASOURCE_PASSWORD = "npg_9sNXHYvZPB4q"
    $env:SPRING_FLYWAY_ENABLED = "true"
    $env:CHAT_DISTRIBUTED_ENABLED = "false"
}

Write-Host "Connecting to Neon at: $env:SPRING_DATASOURCE_URL" -ForegroundColor Gray
Write-Host "Target Port: $env:PORT (avoiding port 8080 conflicts)" -ForegroundColor Gray
Write-Host ""
Write-Host "Starting Spring Boot via Maven Wrapper..." -ForegroundColor Green

& .\mvnw.cmd spring-boot:run
