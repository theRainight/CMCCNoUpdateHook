$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $root 'work'
$src = Join-Path $work 'src\main'
$build = Join-Path $work 'build'
$classes = Join-Path $build 'classes_release'
$dex = Join-Path $build 'dex_release'
$res = Join-Path $build 'compiled_res_release'
$outDir = Join-Path $root 'outputs'

$androidJar = 'F:\flutterSDK\android-sdk-windows\platforms\android-35\android.jar'
$aapt2 = 'F:\flutterSDK\android-sdk-windows\build-tools\35.0.0\aapt2.exe'
$d8 = 'F:\flutterSDK\android-sdk-windows\build-tools\35.0.0\d8.bat'
$zipalign = 'F:\flutterSDK\android-sdk-windows\build-tools\35.0.0\zipalign.exe'
$apksigner = 'F:\flutterSDK\android-sdk-windows\build-tools\35.0.0\apksigner.bat'
$xposedJar = 'C:\Users\theRainight\.codex\skills\lsposed-api102-module\assets\libxposed-api-102.0.0-classes.jar'

function Assert-LastCommand {
    param([string] $Label)
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE`: $Label"
    }
}

Remove-Item -LiteralPath $classes, $dex, $res -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dex, $res, $outDir | Out-Null

$javaFile = Join-Path $src 'java\cn\codex\lsposed\cmccnoupdate\CmccNoUpdateModule.java'
& javac -encoding UTF-8 -source 8 -target 8 -cp "$androidJar;$xposedJar" -d $classes $javaFile
Assert-LastCommand 'javac'

$moduleClassesJar = Join-Path $build 'module-classes.jar'
Remove-Item -LiteralPath $moduleClassesJar -Force -ErrorAction SilentlyContinue
Push-Location $classes
& jar cf $moduleClassesJar .
Assert-LastCommand 'jar classes'
Pop-Location

& $d8 --lib $androidJar --min-api 23 --output $dex $moduleClassesJar
Assert-LastCommand 'd8'

& $aapt2 compile --dir (Join-Path $src 'res') -o $res
Assert-LastCommand 'aapt2 compile'

$unsignedBase = Join-Path $build 'cmcc-noupdate-release-unsigned-base.apk'
$unsigned = Join-Path $build 'cmcc-noupdate-release-unsigned.apk'
$aligned = Join-Path $build 'cmcc-noupdate-release-aligned.apk'
$signed = Join-Path $outDir 'cmcc-noupdate-api102-release.apk'
Remove-Item -LiteralPath $unsignedBase, $unsigned, $aligned, $signed, "$signed.idsig" -Force -ErrorAction SilentlyContinue

$flatFiles = Get-ChildItem -LiteralPath $res -Filter *.flat -Recurse | ForEach-Object { $_.FullName }
& $aapt2 link -o $unsignedBase --manifest (Join-Path $src 'AndroidManifest.xml') -I $androidJar $flatFiles
Assert-LastCommand 'aapt2 link'

Copy-Item -LiteralPath $unsignedBase -Destination $unsigned -Force

Push-Location (Join-Path $src 'resources')
& jar uf $unsigned 'META-INF/xposed/java_init.list' 'META-INF/xposed/module.prop' 'META-INF/xposed/scope.list'
Assert-LastCommand 'jar resources'
Pop-Location

Push-Location $dex
& jar uf $unsigned 'classes.dex'
Assert-LastCommand 'jar dex'
Pop-Location

& $zipalign -p -f 4 $unsigned $aligned
Assert-LastCommand 'zipalign'

$ks = Join-Path $build 'debug.keystore'
if (!(Test-Path -LiteralPath $ks)) {
    & keytool -genkeypair -v -keystore $ks -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
}

& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $signed $aligned
Assert-LastCommand 'apksigner sign'
& $apksigner verify --verbose $signed
Assert-LastCommand 'apksigner verify'
& $aapt2 dump badging $signed
Assert-LastCommand 'aapt2 dump badging'
jar tf $signed | Select-String -Pattern 'META-INF/xposed|classes.dex'
Get-Item -LiteralPath $signed | Select-Object FullName, Length, LastWriteTime
