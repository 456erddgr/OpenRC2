# -*- coding: utf-8 -*-
"""把 app/jniLibs/<abi>/*.so 以 STORED 方式塞进已签名 APK，
随后必须 zipalign -p 4 + apksigner 重签（build-apk.sh 已串好）。

背景：本 ROM 的 PM 对新装 App 强制 abiOverride=armeabi-v7a（docs/09），
所以 armeabi-v7a 必须有；arm64-v8a 有就一起带。"""
import os
import sys
import zipfile

ABIS = ('armeabi-v7a', 'arm64-v8a')

def main():
    src, libdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
    zin = zipfile.ZipFile(src, 'r')
    zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
    for it in zin.infolist():
        if it.filename.startswith('META-INF/'):
            continue
        zi = zipfile.ZipInfo(it.filename, date_time=(1980, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_STORED if it.filename.endswith('.so') else zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, zin.read(it.filename))
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
            zi = zipfile.ZipInfo('lib/%s/%s' % (abi, name), date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            zout.writestr(zi, data)
            n += 1
            print('  + lib/%s/%s %d B' % (abi, name, len(data)))
    zout.close()
    zin.close()
    print('injected %d so -> %s' % (n, out))

if __name__ == '__main__':
    main()
