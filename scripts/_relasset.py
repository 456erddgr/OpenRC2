import subprocess, json, urllib.request, os
p=subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',capture_output=True,text=True)
tok=[l.split('=',1)[1] for l in p.stdout.splitlines() if l.startswith('password=')][0]
H={'Authorization':'token '+tok,'Accept':'application/vnd.github+json','User-Agent':'openrc2'}
rel=json.load(urllib.request.urlopen(urllib.request.Request(
 'https://api.github.com/repos/456erddgr/OpenRC2/releases',headers=H),timeout=30))
r=[d for d in rel if d['tag_name']=='v1.20.9'][0]
for a in r['assets']:
    req=urllib.request.Request(a['url'],method='DELETE',headers=H)
    urllib.request.urlopen(req,timeout=30); print('deleted old', a['name'], a['size'])
apk=r'E:\我的项目\RC2-EdgeTX\dist\RC2ETX-1.20.9.apk'
data=open(apk,'rb').read()
up=r['upload_url'].split('{')[0]+'?name=RC2ETX-1.20.9.apk'
req=urllib.request.Request(up,data=data,method='POST',
  headers={**H,'Content-Type':'application/vnd.android.package-archive'})
d=json.load(urllib.request.urlopen(req,timeout=180))
print('uploaded', d['name'], d['size'], d['browser_download_url'])
