#!/usr/bin/env python3
"""Inspect built distribution payloads; never infer runtime readiness from packaging."""
import argparse,hashlib,json,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('apks',nargs='+',type=Path);a=p.parse_args()
for apk in a.apks:
 with zipfile.ZipFile(apk) as archive:
  names=archive.namelist();bundle=archive.read('assets/runtime.cjs');manifest=json.loads(archive.read('assets/runtime-manifest.json'))
  allowed_native={'lib/arm64-v8a/libngrok_java.so'}
  forbidden=[n for n in names if n not in allowed_native and any(x in n.lower() for x in ['ngrok','node_modules/','auth.json','.jks','relay.token'])]
  if forbidden:raise SystemExit(f'Unexpected packaged files in {apk.name}: {forbidden}')
  if len(bundle)!=manifest['bytes']:raise SystemExit('Bundle bytes disagree with runtime manifest')
  native={i.filename:i.file_size for i in archive.infolist() if i.filename.startswith('lib/')}
  print(json.dumps({'apk':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'compressedApkBytes':apk.stat().st_size,'runtimeBytes':len(bundle),'nativeUncompressedBytes':native,'packagingOnly':True}))
