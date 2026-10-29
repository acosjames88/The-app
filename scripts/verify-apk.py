import sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as apk:
    names = apk.namelist()
    for abi in ('armeabi-v7a', 'arm64-v8a', 'x86_64'):
        for lib in ('snescore', 'snesplayer'):
            assert f'lib/{abi}/lib{lib}.so' in names, (abi, lib)
    assert 'assets/star-catcher.sfc' in names
    assert 'assets/SNES9X-LICENSE.txt' in names
    assert not any(n.endswith(('.html', '.js', '.wasm')) for n in names)
    for name in names:
        if name.endswith('.dex'):
            data = apk.read(name)
            for forbidden in (b'Landroid/webkit/WebView;', b'Landroid/webkit/WebViewClient;', b'cdn.emulatorjs.org'):
                assert forbidden not in data, forbidden
print('PASS: bundled SNES cores for ARM32, ARM64, x86_64; offline game; notices; no browser or web runtime.')
