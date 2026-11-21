import hashlib
import json
import pathlib
import tomllib
import zipfile

jar = pathlib.Path("build/libs/bbs-geo-importer.jar")
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), "duplicate archive entries"
    fabric = json.loads(archive.read("fabric.mod.json"))
    forge, = tomllib.loads(archive.read("META-INF/mods.toml").decode())["mods"]
    assert fabric["id"] == forge["modId"] == "bbs_geo_importer"
    assert fabric["version"] == forge["version"] == "1.5.22"
    assert fabric["environment"] == "client"
    assert fabric["entrypoints"]["client"] == ["local.bbsgeoimporter.FabricEntrypoint"]
    assert fabric["mixins"] == ["bbs_geo_importer.mixins.json"]
    assert "MixinConfigs: bbs_geo_importer.mixins.json" in archive.read("META-INF/MANIFEST.MF").decode()
    mixins = json.loads(archive.read(fabric["mixins"][0]))
    assert not mixins["required"] and len(mixins["client"]) == len(set(mixins["client"]))
    for member in mixins["client"]:
        assert mixins["package"].replace(".", "/") + "/" + member + ".class" in names
    assert mixins["plugin"].replace(".", "/") + ".class" in names
    for member in ("ClientBootstrap", "ForgeEntrypoint", "FabricEntrypoint", "FabricSources",
                   "GeoImporter", "StartupReload", "FormGridViewportTransforms", "SharedModelAssets"):
        assert f"local/bbsgeoimporter/{member}.class" in names
    assert "pack.mcmeta" in names and "META-INF/coremods.json" in names
    assert not any(n.startswith(("net/fabricmc/", "net/minecraftforge/", "com/google/gson/")) for n in names)
    for n in names:
        if n.endswith(".class"):
            assert int.from_bytes(archive.read(n)[6:8], "big") == 61

result = [{"path": str(jar), "loader": "forge+fabric", "bytes": jar.stat().st_size,
           "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}]
pathlib.Path("build/package-check.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
