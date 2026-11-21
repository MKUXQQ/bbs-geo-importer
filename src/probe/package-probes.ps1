$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$libRoot='E:/我的世界/.minecraft/libraries'
$versions='E:/我的世界/.minecraft/versions'
$output=New-Item -ItemType Directory -Force -Path "$root/build/dual-probe"
$artifact=(Resolve-Path "$root/build/libs/bbs-geo-importer.jar").Path
foreach($mode in 'Fabric','Forge'){
    $instance=if($mode -eq 'Fabric'){'1.20.1-FS-风干兔肉包'}else{'bbs1.20.1动画'}
    $game="$versions/$instance"
    $profile=Get-Content -LiteralPath "$game/$instance.json" -Raw | ConvertFrom-Json
    $libraryPaths=foreach($library in $profile.libraries){
        $rel=$library.downloads.artifact.path
        if(!$rel){
            $parts=$library.name.Split(':');$stem="$($parts[1])-$($parts[2])"
            $suffix=if($parts.Length -gt 3){"-$($parts[3])"}else{''}
            $rel="$($parts[0].Replace('.','/'))/$($parts[1])/$($parts[2])/$stem$suffix.jar"
        }
        $path=Join-Path $libRoot $rel
        if([IO.File]::Exists($path)){$path}
    }
    if($mode -eq 'Fabric'){
        $mc=Get-ChildItem -LiteralPath "$game/.fabric/remappedJars/minecraft-1.20.1-0.20.1" -Filter 'client-intermediary.jar'
        $api=[IO.Compression.ZipFile]::OpenRead("$game/mods/fabric-api-0.92.12+1.20.1.jar")
        try{$extra=@(foreach($entry in $api.Entries){if($entry.Name -match '^fabric-(resource-loader-v0|api-base)-.*\.jar$'){
            $dest=Join-Path $output $entry.Name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$dest,$true)
            $dest
        }})}finally{$api.Dispose()}
        $extra+=@($mc.FullName)
    }else{
        $extra=@(foreach($module in 'forge','fmlcore','javafmllanguage'){
            Get-ChildItem -LiteralPath "$libRoot/net/minecraftforge/$module/1.20.1-47.4.28" -Filter '*.jar' | ForEach-Object {$_.FullName}
        })
    }
    $classes=New-Item -ItemType Directory -Force -Path "$output/$mode"
    $cp=( (@($classes.FullName,$artifact)+$extra+$libraryPaths) | Select-Object -Unique ) -join ';'
    $main="${mode}PackageProbe"
    & 'E:/JAVA/bin/javac.exe' -proc:none -encoding UTF-8 --release 17 -classpath $cp -d $classes.FullName "$PSScriptRoot/java/local/bbsgeoimporter/$main.java"
    if($LASTEXITCODE){exit $LASTEXITCODE}
    Push-Location -LiteralPath $output
    try{& 'E:/JAVA/bin/java.exe' '-Xmx384m' '-XX:ActiveProcessorCount=2' '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED' '--add-opens=java.base/java.util.jar=ALL-UNNAMED' -cp $cp "local.bbsgeoimporter.$main" $artifact $output.FullName;if($LASTEXITCODE){exit $LASTEXITCODE}}finally{Pop-Location}
}
