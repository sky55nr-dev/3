param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr'
)

$ErrorActionPreference = 'Stop'

function Invoke-BuildTool {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable exited with code $LASTEXITCODE"
    }
}

Push-Location -LiteralPath $PSScriptRoot
try {
    $buildTools = Join-Path $Sdk 'build-tools\36.0.0'
    $signer = Join-Path $Sdk 'build-tools\34.0.0\apksigner.bat'
    $signerJar = Join-Path $Sdk 'build-tools\34.0.0\lib\apksigner.jar'
    $platform = Join-Path $Sdk 'platforms\android-35\android.jar'
    $aapt = Join-Path $buildTools 'aapt2.exe'
    $env:JAVA_HOME = $Jdk
    New-Item -ItemType Directory -Path .build, .build/classes, .build/template-classes, .build/assets, .build/signer -Force | Out-Null
    Get-ChildItem -LiteralPath .build/classes, .build/template-classes -Recurse -File -Filter '*.class' |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    Push-Location -LiteralPath .build/signer
    try {
        Invoke-BuildTool "$Jdk\bin\jar.exe" @('xf', $signerJar, 'com/android/apksig')
    } finally {
        Pop-Location
    }
    Invoke-BuildTool "$Jdk\bin\jar.exe" @('cf', '.build/apksig.jar', '-C', '.build/signer', 'com/android/apksig')
    Invoke-BuildTool $aapt @('compile', '--dir', 'template/res', '-o', '.build/template-res.zip')
    Invoke-BuildTool $aapt @('link', '-I', $platform, '--manifest', 'template/AndroidManifest.xml',
        '-o', '.build/template-resources.apk', '.build/template-res.zip')
    Invoke-BuildTool "$Jdk\bin\javac.exe" @('-encoding', 'UTF-8', '--release', '8',
        '-classpath', $platform, '-d', '.build/template-classes', 'template/LinkActivity.java')
    Invoke-BuildTool "$Jdk\bin\jar.exe" @('cf', '.build/template-classes.jar', '-C', '.build/template-classes', '.')
    Invoke-BuildTool "$buildTools\d8.bat" @('--lib', $platform, '--min-api', '26',
        '--output', '.build/template-dex.zip', '.build/template-classes.jar')
    Invoke-BuildTool "$Jdk\bin\jar.exe" @('xf', '.build/template-dex.zip', 'classes.dex')
    Move-Item -LiteralPath classes.dex -Destination .build/classes.dex -Force
    Copy-Item -LiteralPath .build/template-resources.apk -Destination .build/assets/link-template.apk -Force
    Invoke-BuildTool "$Jdk\bin\jar.exe" @('uf', '.build/assets/link-template.apk', '-C', '.build', 'classes.dex')
    Invoke-BuildTool $aapt @('compile', '--dir', 'res', '-o', '.build/resources.zip')
    Invoke-BuildTool $aapt @('link', '-I', $platform, '--manifest', 'AndroidManifest.xml',
        '-A', '.build/assets', '-o', '.build/resources.apk', '.build/resources.zip')
    Invoke-BuildTool "$Jdk\bin\javac.exe" @('-encoding', 'UTF-8', '--release', '8',
        '-classpath', "$platform;.build/apksig.jar", '-d', '.build/classes',
        'src/MainActivity.java', 'src/ApkFactory.java', 'src/GeneratedApkProvider.java', 'src/IconImage.java')
    Invoke-BuildTool "$Jdk\bin\jar.exe" @('cf', '.build/classes.jar', '-C', '.build/classes', '.')
    Invoke-BuildTool "$buildTools\d8.bat" @('--lib', $platform, '--min-api', '23',
        '--output', '.build/dex.zip', '.build/classes.jar', '.build/apksig.jar')
    Invoke-BuildTool "$Jdk\bin\jar.exe" @('xf', '.build/dex.zip', 'classes.dex')
    Move-Item -LiteralPath classes.dex -Destination .build/classes.dex -Force
    Copy-Item -LiteralPath .build/resources.apk -Destination .build/unsigned.apk -Force
    Invoke-BuildTool "$Jdk\bin\jar.exe" @('uf', '.build/unsigned.apk', '-C', '.build', 'classes.dex')
    Invoke-BuildTool "$buildTools\zipalign.exe" @('-f', '4', '.build/unsigned.apk', '.build/aligned.apk')

    $key = '.build/link-launcher.keystore'
    if (!(Test-Path -LiteralPath $key)) {
        Invoke-BuildTool "$Jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $key,
            '-storepass', 'android', '-alias', 'link-launcher', '-dname', 'CN=Link Launcher',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
    }
    Invoke-BuildTool $signer @('sign', '--v3-signing-enabled', 'false', '--v4-signing-enabled',
        'false', '--ks', $key, '--ks-pass', 'pass:android', '--out',
        '.build/LinkLauncher.apk', '.build/aligned.apk')
    Invoke-BuildTool $signer @('verify', '--verbose', '.build/LinkLauncher.apk')
    Invoke-BuildTool "$buildTools\zipalign.exe" @('-c', '4', '.build/LinkLauncher.apk')
    Copy-Item -LiteralPath .build/LinkLauncher.apk -Destination LinkLauncher.apk -Force
} finally {
    Pop-Location
}
