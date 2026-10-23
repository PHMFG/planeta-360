# Executar na pasta do projeto:
# powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\atualizar-front-end.ps1"
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path
$front = Join-Path $raiz 'frontend'
$destino = Join-Path $raiz 'src\main\resources\static'
$dist = Join-Path $front 'dist'

Push-Location $front
try {
    if (-not (Test-Path 'node_modules')) {
        Write-Host 'Instalando dependencias do front-end...'
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci falhou.' }
    }
    Write-Host 'Compilando front-end...'
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Compilacao falhou. Arquivos publicados nao foram alterados.' }
} finally {
    Pop-Location
}

if (-not (Test-Path (Join-Path $dist 'index.html')) -or
    -not (Test-Path (Join-Path $dist 'app-react.html'))) {
    throw 'A compilacao nao gerou as duas paginas HTML esperadas.'
}

New-Item -ItemType Directory -Force -Path $destino | Out-Null
# Arquivos gerados da compilacao anterior podem ter nomes de hash obsoletos.
$assetsAntigos = Join-Path $destino 'assets'
if (Test-Path $assetsAntigos) { Remove-Item $assetsAntigos -Recurse -Force }
Copy-Item -Path (Join-Path $dist '*') -Destination $destino -Recurse -Force
Write-Host 'Atualizado: / = HTML novo; /app-react.html = React com API.' -ForegroundColor Green
