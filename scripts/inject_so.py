# -*- coding: utf-8 -*-
"""把 app/jniLibs/<abi>/*.so 以 STORED 方式塞进已签名 APK，顺带把 app/assets 也打进去，
随后必须 zipalign -p 4 + apksigner 重签（build-apk.sh 已串好）。

背景：
- 本 ROM 的 PM 对新装 App 强制 abiOverride=armeabi-v7a（docs/09），所以 armeabi-v7a 必须有。
- aapt2 那条链不带 -A，assets 不会自动进去；而 sd_seed（首次运行铺 SD 骨架）必须靠它，
  否则新机器 etx_init 返回 -1（RC Pro 实测）。
"""
import os
import sys
import zipfile

ABIS = ('armeabi-v7a', 'arm64-v8a')

def _zi(name, stored=False):
    zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    return zi

def main():
    src, libdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
    assets_dir = sys.argv[4] if len(sys.argv) > 4 else None
    zin = zipfile.ZipFile(src, 'r')
    zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
    for it in zin.infolist():
        if it.filename.startswith('META-INF/'):
            continue
        zout.writestr(_zi(it.filename, it.filename.endswith('.so')), zin.read(it.filename))

    n = 0
    for abi in ABIS:
        d = os.path.join(libdir, abi)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith('.so'):
                continue
            with open(os.path.join(d, name), 'rb') as f:
                data = f.read()
            zout.writestr(_zi('lib/%s/%s' % (abi, name), True), data)
            n += 1
            print('  + lib/%s/%s %d B' % (abi, name, len(data)))

    # assets：aapt2 link 那一步已经用 -A assets 把 app/assets 打进去了（build-apk.ps1:50），
    # 这里再写一遍就会出现同名条目 → apksigner 直接报
    #   "Multiple ZIP entries with the same name: assets/sd_seed/RADIO/radio.yml"。
    # 所以只补 base.apk 里**没有**的条目（正常情况下一个都不用补）。
    existing = set(zout.namelist())
    a = 0
    skipped = 0
    if assets_dir and os.path.isdir(assets_dir):
        for root, _dirs, files in os.walk(assets_dir):
            for fn in sorted(files):
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, assets_dir).replace(chr(92), '/')
                name = 'assets/' + rel
                if name in existing:
                    skipped += 1
                    continue
                with open(full, 'rb') as f:
                    data = f.read()
                zout.writestr(_zi(name), data)
                a += 1

    zout.close()
    zin.close()
    print('injected %d so, %d asset files (%d already in base) -> %s' % (n, a, skipped, out))

if __name__ == '__main__':
    main()
