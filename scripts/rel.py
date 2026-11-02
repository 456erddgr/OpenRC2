# -*- coding: utf-8 -*-
"""建/更新 GitHub Release 并上传 APK。用法: python scripts/rel.py v1.28.0 dist/RC2ETX-1.28.0.apk [说明文件]"""
import subprocess, json, urllib.request, sys, os

tag = sys.argv[1]
apk = sys.argv[2]
notes_file = sys.argv[3] if len(sys.argv) > 3 else None
apk_name = os.path.basename(apk)

p = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n',
                   capture_output=True, text=True)
tok = [l.split('=', 1)[1] for l in p.stdout.splitlines() if l.startswith('password=')][0]
H = {'Authorization': 'token ' + tok, 'Accept': 'application/vnd.github+json',
     'User-Agent': 'openrc2'}
API = 'https://api.github.com/repos/456erddgr/OpenRC2'

body = 'OpenRC2 %s\n\n作者：软糖布丁\n' % tag
if notes_file and os.path.exists(notes_file):
    body += '\n' + open(notes_file, encoding='utf-8').read()

try:
    rels = json.load(urllib.request.urlopen(urllib.request.Request(API + '/releases', headers=H), timeout=30))
except Exception as e:
    print('list releases failed', e); sys.exit(1)

cur = [d for d in rels if d['tag_name'] == tag]
if cur:
    r = cur[0]
    for a in r['assets']:
        if a['name'] == apk_name:
            urllib.request.urlopen(urllib.request.Request(a['url'], method='DELETE', headers=H), timeout=30)
            print('deleted old asset', apk_name, a['size'])
else:
    data = json.dumps({'tag_name': tag, 'name': 'OpenRC2 ' + tag, 'body': body,
                       'draft': False, 'prerelease': False}).encode()
    req = urllib.request.Request(API + '/releases', data=data, method='POST',
                                 headers={**H, 'Content-Type': 'application/json'})
    r = json.load(urllib.request.urlopen(req, timeout=60))
    print('created release', r['tag_name'], r['id'])

data = open(apk, 'rb').read()
up = r['upload_url'].split('{')[0] + '?name=' + apk_name
req = urllib.request.Request(up, data=data, method='POST',
                             headers={**H, 'Content-Type': 'application/vnd.android.package-archive'})
d = json.load(urllib.request.urlopen(req, timeout=300))
print('uploaded', d['name'], d['size'], d['browser_download_url'])
