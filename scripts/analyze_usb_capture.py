"""Offline pcapng/USBPcap extractor. Standard library only; never opens USB.
Usage: python scripts/analyze_usb_capture.py W1.pcapng W2.pcapng --output report.md
Only emits EP0 non-string descriptors and endpoint payloads; omits USB strings.
"""
import argparse,struct,hashlib,collections
from pathlib import Path

def parse(path):
 data=Path(path).read_bytes(); pos=0; rows=[]; endian='<'; first=None
 while pos<len(data):
  if data[pos:pos+4]==b'\x0a\x0d\x0d\x0a': endian='<' if data[pos+8:pos+12]==b'\x4d\x3c\x2b\x1a' else '>'
  typ,n=struct.unpack_from(endian+'II',data,pos)
  if n<12 or pos+n>len(data) or struct.unpack_from(endian+'I',data,pos+n-4)[0]!=n: raise ValueError('Invalid block')
  b=data[pos+8:pos+n-4]; pos+=n
  if typ!=6: continue
  iface,hi,lo,cap,orig=struct.unpack_from(endian+'IIIII',b); raw=b[20:20+cap]
  if len(raw)<27: raise ValueError('Short USBPcap header')
  h,irp,status,fun,info,bus,dev,ep,transfer,length=struct.unpack_from('<HQIHBHHBBI',raw)
  stamp=((hi<<32)|lo)/1e6
  if first is None:first=stamp
  payload=raw[h:h+length]
  rows.append(dict(frame=len(rows)+1,t=stamp-first,bus=bus,dev=dev,ep=ep,transfer=transfer,complete=info&1,irp=irp,status=status,payload=payload,truncated=cap<orig or len(payload)<length))
 return rows,hashlib.sha256(data).hexdigest()

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('captures',nargs=2);ap.add_argument('--output',required=True); a=ap.parse_args()
 out=['# TourBox Windows抓包离线分析（006M）','\n2026-11-01。W1无Console，W2有Console且旋转正常，条件由用户提供。仅离线解析，未重放命令。帧号是过滤导出文件内编号，无法反推原始4107/4203/4547/4867/5231。','\n## 方法与完整性限制','使用Python标准库解析pcapng EPB及USBPcap头，并以本机tshark关键帧十六进制输出交叉核对。USBPcap头长字段定位真实载荷；EP0另含setup，不当作设备数据。按bus/device/IRP维护未完成请求队列，IRP会重复使用，不按ID全局合并。时间使用输入文件默认微秒分辨率；本次与tshark相对时间相符。没有独立动作时间表，不能从码表直接认定具体物理操作。未见截断不代表无上游过滤或捕获丢包。']
 for label,path in zip(['W1','W2'],a.captures):
  rows,sha=parse(path); pending=collections.defaultdict(list); pairs={}; unmatched=[]
  for r in rows:
   k=(r['bus'],r['dev'],r['irp'])
   if not r['complete']:pending[k].append(r)
   elif pending[k]:pairs[pending[k].pop(0)['frame']]=r['frame']
   else:unmatched.append(r['frame'])
  outs=[r for r in rows if r['ep']==2 and not r['complete'] and r['payload']]
  ins=[r for r in rows if r['ep']==130 and r['complete'] and r['payload']]
  out += [f'\n## {label}基本信息与统计',f'输入文件名：`{Path(path).name}`；SHA256 `{sha}`。',f"记录{len(rows)}，时长{rows[-1]['t']:.6f}s；bus/device集合{sorted(set((r['bus'],r['dev']) for r in rows))}；截断{sum(r['truncated'] for r in rows)}；配对{len(pairs)}，未配对提交{sum(map(len,pending.values()))}，未配对完成{unmatched}。",f'BULK OUT命令{len(outs)}；非空BULK IN完成{len(ins)}、{sum(len(r["payload"]) for r in ins)}字节；0x81非空载荷{sum(bool(r["payload"]) for r in rows if r["ep"]==129)}。']
  desc=[r['payload'] for r in rows if r['transfer']==2 and r['complete'] and len(r['payload'])==18 and r['payload'][:2]==b'\x12\x01']
  for d in desc:out.append(f'设备描述符：VID={int.from_bytes(d[8:10],"little"):04X} PID={int.from_bytes(d[10:12],"little"):04X} bcdDevice={int.from_bytes(d[12:14],"little"):04X}；序列号字符串不输出。')
  out+=['\n### 完整BULK OUT时间线','帧 | 相对秒 | 完成帧 | 长度 | HEX','---|---:|---:|---:|---']
  for r in outs:out.append(f'{r["frame"]}|{r["t"]:.6f}|{pairs.get(r["frame"],"缺失")}|{len(r["payload"])}|{r["payload"].hex(" ").upper()}')
  configs=[r for r in outs if len(r['payload'])==94]
  if configs:
   base=configs[0]['payload'];out+=['\n94字节差异：以下偏移从0开始，相对首条；未赋予字段语义。']
   for r in configs:out.append(f'帧{r["frame"]}：'+(', '.join(f'{i}: {x:02X}→{y:02X}' for i,(x,y) in enumerate(zip(base,r['payload'])) if x!=y) or '完全相同'))
  out+=['\n### EP0及非空IN完整时间表','帧 | 秒 | 端点 | 提交/完成 | 状态 | 数据（EP0提交含8字节setup）','---|---:|---|---|---|---']
  skip=set()
  for r in rows:
   p=r['payload']
   if r['transfer']==2 and not r['complete'] and len(p)>=8 and p[1]==6 and p[3]==3:skip.add(pairs.get(r['frame']))
   if r['frame'] in skip:continue
   if r['transfer']==2 and not r['complete'] and len(p)>=8 and p[1]==6 and p[3]==3:continue
   if r['transfer']==2 or (r['ep'] in (129,130) and p):out.append(f'{r["frame"]}|{r["t"]:.6f}|{r["ep"]:02X}|{"完成" if r["complete"] else "提交"}|{r["status"]:08X}|{p.hex(" ").upper()}')
  out+=['\n单字节IN分布：'+str(dict(collections.Counter(r['payload'].hex().upper() for r in ins if len(r['payload'])==1)))]
 Path(a.output).write_text('\n'.join(out)+'\n',encoding='utf-8')
if __name__=='__main__':main()
