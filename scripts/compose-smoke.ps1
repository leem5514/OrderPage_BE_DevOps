param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$TimeoutSeconds = 120,
    [int]$IntervalSeconds = 5
)

$ErrorActionPreference = "Stop"

function Invoke-SmokeRequest {
    param(
        [string]$Uri,
        [string]$Name
    )

    try {
        $response = Invoke-RestMethod -Uri $Uri -Method Get
        Write-Host "[OK] $Name -> $Uri"
        return $response
    } catch {
        throw "[FAIL] $Name -> $Uri : $($_.Exception.Message)"
    }
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$healthUri = "$BaseUrl/actuator/health"

Write-Host "Waiting for backend health endpoint: $healthUri"

do {
    try {
        $health = Invoke-RestMethod -Uri $healthUri -Method Get
        if ($health.status -eq "UP") {
            Write-Host "[OK] Backend health is UP"
            break
        }

        Write-Host "Health status is '$($health.status)'. Waiting..."
    } catch {
        Write-Host "Backend is not ready yet. Waiting..."
    }

    Start-Sleep -Seconds $IntervalSeconds
} while ((Get-Date) -lt $deadline)

if ((Get-Date) -ge $deadline) {
    throw "Backend did not become healthy within $TimeoutSeconds seconds."
}

Invoke-SmokeRequest -Uri "$BaseUrl/product/list" -Name "Public product list API" | Out-Null

Write-Host "Smoke test completed successfully."
