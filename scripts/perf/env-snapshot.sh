#!/usr/bin/env bash
powershell -NoProfile -Command "
\$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1)
\$mem = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB
\$os = (Get-CimInstance Win32_OperatingSystem).Caption
Write-Host ('CPU: ' + \$cpu.Name.Trim() + ' (' + \$cpu.NumberOfCores + 'C/' + \$cpu.NumberOfLogicalProcessors + 'T)')
Write-Host ('RAM: ' + [math]::Round(\$mem,1) + ' GB')
Write-Host ('OS: ' + \$os)
"
docker version --format 'Docker: {{.Server.Version}}'
docker exec sport-verify-mysql mysql -uroot -proot -N -e "SELECT VERSION()" 2>/dev/null
