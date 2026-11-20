import pathlib, zipfile, json, hashlib
libs=pathlib.Path('build/libs')
result=[]
for loader in ('forge',):
    jar=libs/f'bbs-geo-importer-1.20.1-{loader}-1.5.20.jar'
    with zipfile.ZipFile(jar) as z:
        names=set(z.namelist())
        assert 'pack.mcmeta' in names
        mix=json.loads(z.read('bbs_geo_importer.mixins.json')); assert mix['required'] is False and mix['client']==['ModelWatchMixin','ModelCategoryMixin','GeckoRendererMixin','GeckoModelMixin','SharedAssetsMixin','ModelRefreshMixin','MorphingPanelMixin','ListPreviewMixin','FormGridViewportMixin','VaoOwnershipMixin','ModelManagementMixin','SettingsManagementMixin'] and mix['plugin']=='local.bbsgeoimporter.mixin.CaptureMixinPlugin'
        assert {'local/bbsgeoimporter/FormGridViewport.class','local/bbsgeoimporter/FormGridViewportTransforms.class','local/bbsgeoimporter/mixin/FormGridViewportMixin.class'} <= names
        assert 'local/bbsgeoimporter/mixin/MorphingManagementMixin.class' not in names
        assert 'local/bbsgeoimporter/mixin/VaoOwnershipMixin.class' in names
        assert 'local/bbsgeoimporter/mixin/ModelCategoryMixin.class' in names
        assert 'local/bbsgeoimporter/GeoImporter.class' in names
        assert all('local/bbsgeoimporter/'+name+'.class' in names for name in ('StartupReload','ModelManagement','ModelWatchEvents','ListPreviewRequests','ListThumbnailGpu','ListPreviewSetup','ListTextureWarmup','ModelManagementUi','ListModelResidency','CubicMeshPreparation','CubicSetupTransforms','VaoBufferOwnership','NativeModelLoading'))
        assert 'local/bbsgeoimporter/FabricSources.class' in names
        if loader=='fabric':
            m=json.loads(z.read('fabric.mod.json')); assert m['version']=='1.5.20' and m['environment']=='client'
            assert 'local/bbsgeoimporter/FabricEntrypoint.class' in names
            assert 'local/bbsgeoimporter/BbsGeoImporter.class' not in names
            assert 'META-INF/mods.toml' not in names
            assert 'bbs_geo_importer.mixins.json' in m['mixins']
        else:
            assert 'version="1.5.20"' in z.read('META-INF/mods.toml').decode()
            assert 'fabric.mod.json' not in names
            assert 'MixinConfigs: bbs_geo_importer.mixins.json' in z.read('META-INF/MANIFEST.MF').decode()
            assert 'local/bbsgeoimporter/ForgeEntrypoint.class' in names
            entry=z.read('local/bbsgeoimporter/ForgeEntrypoint.class'); assert b'm_6769_' in entry, 'Production reload barrier mapping missing'
            assert 'local/bbsgeoimporter/FabricEntrypoint.class' not in names
        assert not any(n.startswith(('net/fabricmc/','net/minecraftforge/','com/google/gson/')) for n in names)
        for n in names:
            if n.endswith('.class'): assert int.from_bytes(z.read(n)[6:8],'big')==61
    result.append(dict(loader=loader,path=str(jar),bytes=jar.stat().st_size,sha256=hashlib.sha256(jar.read_bytes()).hexdigest()))
pathlib.Path('build/package-check.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
