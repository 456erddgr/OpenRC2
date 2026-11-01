import subprocess, json, urllib.request
p=subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',capture_output=True,text=True)
tok=[l.split('=',1)[1] for l in p.stdout.splitlines() if l.startswith('password=')][0]
H={'Authorization':'token '+tok,'Accept':'application/vnd.github+json','User-Agent':'openrc2'}
rel=json.load(urllib.request.urlopen(urllib.request.Request(
  'https://api.github.com/repos/456erddgr/OpenRC2/releases',headers=H),timeout=30))
rid=[d['id'] for d in rel if d['tag_name']=='v1.20.9'][0]
body=('## 这一版\n\n'
 '- **USB 手柄摇杆能动起来了**：重启后 `/dev/hidg0` 变 `root:600`，现在自动 chmod 再 open，并每 800ms 重试、节点缺失自动重建 gadget。\n'
 '- **采样从 ~90Hz 提到 ~250Hz**：发包挪到独立 4ms 节拍线程，不跟控制环抖动（真机实测 1349 帧 / 5s ≈ 270Hz）。\n'
 '- 修 EdgeTX 菜单按 ENT 闪退（`lv_table_set_row_cnt+132` UAF，vendor 4 处空指针保护）。\n'
 '- USB 选择弹窗重做深色卡片，恢复「插着线也弹」，20 秒自动收起。\n'
 '- 电脑端 bat 改用 `/debug_ramdisk/su`。\n\n'
 '> USB 手柄**需要一次性 root 授权**（建 gadget 是内核操作），授权时选「始终允许」，之后开机自动重建。\n\n'
 '## 已知问题\n\n'
 '- 「模型管理」网格不显示模型格（数据正常，tile 渲染问题，排查中）\n'
 '- 「APP/LUA脚本」页入口待确认\n\n'
 '国内镜像：<https://1843626542.share.123pan.cn/123pan/VX07Td-v8SoA?pwd=nRmH> 提取码 `nRmH`')
req=urllib.request.Request('https://api.github.com/repos/456erddgr/OpenRC2/releases/%d'%rid,
    data=json.dumps({'tag_name':'v1.20.9','name':'OpenRC2 1.20.9 — USB 手柄 250Hz + 重启自动恢复','body':body}).encode(),
    method='PATCH',headers=H)
d=json.load(urllib.request.urlopen(req,timeout=30))
print('OK', d['id'], d['tag_name'], d['name'].encode('utf-8')[:40])
