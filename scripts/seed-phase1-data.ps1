param(
    [string]$MysqlHost = '192.168.100.128',
    [int]$MysqlPort = 3306,
    [string]$MysqlUser = 'root',
    [string]$MysqlPassword = '1234',
    [string]$RedisHost = '192.168.100.128',
    [int]$RedisPort = 6379,
    [string]$RedisPassword = '123321'
)

$ErrorActionPreference = 'Stop'
$mysql = 'D:\develop\mysql-8.0.34-winx64\bin\mysql.exe'
$sqlFile = Join-Path $PSScriptRoot '..\sql\phase1_test_data.sql'
if (-not (Test-Path $mysql)) { throw "mysql client not found: $mysql" }
$sql = Get-Content -Raw -Encoding UTF8 $sqlFile
$sql | & $mysql '-h' $MysqlHost '-P' $MysqlPort "-u${MysqlUser}" "-p${MysqlPassword}" '--default-character-set=utf8mb4'
if ($LASTEXITCODE -ne 0) { throw 'MySQL seed failed' }

function Invoke-RedisCommand([string[]]$Parts) {
    $tcp = [System.Net.Sockets.TcpClient]::new($RedisHost, $RedisPort)
    try {
        $stream = $tcp.GetStream()
        $request = "*$($Parts.Count)`r`n"
        foreach ($part in $Parts) {
            $bytes = [Text.Encoding]::UTF8.GetBytes([string]$part)
            $request += "`$$($bytes.Length)`r`n$part`r`n"
        }
        $bytes = [Text.Encoding]::UTF8.GetBytes($request)
        $stream.Write($bytes, 0, $bytes.Length)
        $buffer = New-Object byte[] 65536
        $count = $stream.Read($buffer, 0, $buffer.Length)
        return [Text.Encoding]::UTF8.GetString($buffer, 0, $count)
    } finally { $tcp.Close() }
}

function Redis([string[]]$Parts) {
    $tcp = [System.Net.Sockets.TcpClient]::new($RedisHost, $RedisPort)
    try {
        $stream = $tcp.GetStream()
        function Send-RedisRequest($command) {
            $request = "*$($command.Count)`r`n"
            foreach ($part in $command) {
                $partBytes = [Text.Encoding]::UTF8.GetBytes([string]$part)
                $request += "`$($partBytes.Length)`r`n$part`r`n"
            }
            $requestBytes = [Text.Encoding]::UTF8.GetBytes($request)
            $stream.Write($requestBytes, 0, $requestBytes.Length)
            $buffer = New-Object byte[] 65536
            $count = $stream.Read($buffer, 0, $buffer.Length)
            return [Text.Encoding]::UTF8.GetString($buffer, 0, $count)
        }
        $authResult = Send-RedisRequest @('AUTH', $RedisPassword)
        if ($authResult -notmatch '\+OK') { throw "Redis AUTH failed: $authResult" }
        return Send-RedisRequest $Parts
    } finally { $tcp.Close() }
}

for ($type = 1; $type -le 2; $type++) {
    foreach ($row in @(
        @{ id = 1; lon = 120.149192; lat = 30.316078 }, @{ id = 2; lon = 120.151505; lat = 30.333422 },
        @{ id = 3; lon = 120.151954; lat = 30.324970 }, @{ id = 4; lon = 120.146659; lat = 30.312742 },
        @{ id = 5; lon = 120.157780; lat = 30.310633 }, @{ id = 6; lon = 120.148603; lat = 30.318618 },
        @{ id = 7; lon = 120.124691; lat = 30.336819 }, @{ id = 8; lon = 120.150526; lat = 30.325231 },
        @{ id = 9; lon = 120.150598; lat = 30.325251 }, @{ id = 10; lon = 120.149093; lat = 30.324666 },
        @{ id = 11; lon = 120.158530; lat = 30.310002 }, @{ id = 12; lon = 120.149830; lat = 30.312110 },
        @{ id = 13; lon = 120.130453; lat = 30.327655 }, @{ id = 14; lon = 120.128958; lat = 30.337252 }
    )) { [void](Redis @('GEOADD', "shop:geo:$type", $row.lon, $row.lat, $row.id)) }
}

foreach ($stock in @(@{ id = 1; value = 200 }, @{ id = 4; value = 50 }, @{ id = 5; value = 60 })) {
    [void](Redis @('SET', "seckill:stock:{$($stock.id)}", $stock.value))
}
Write-Output 'Phase 1 seed completed: MySQL vouchers/blogs/comments and Redis GEO/stock are ready.'
