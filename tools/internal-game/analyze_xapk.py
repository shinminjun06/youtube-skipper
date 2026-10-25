#!/usr/bin/env python3
"""Offline XAPK HEK header census. No decryption or modification."""
import sys,os,zipfile,io,json,hashlib
from collections import Counter

def inspect(filename):
    result={'name':os.path.basename(filename),'sha256':None,'apks':[],'hek':{},'encrypted_js':0,'encrypted_json':0}
    h=hashlib.sha256()
    with open(filename,'rb') as file:
        for chunk in iter(lambda:file.read(1048576),b''):h.update(chunk)
    result['sha256']=h.hexdigest(); kinds=Counter()
    with zipfile.ZipFile(filename) as z:
        for path in z.namelist():
            if not path.endswith('.apk'):continue
            with zipfile.ZipFile(io.BytesIO(z.read(path))) as apk:
                result['apks'].append(path)
                for name in apk.namelist():
                    if not name.endswith(('.js','.jsc','.json')):continue
                    with apk.open(name) as f:prefix=f.read(8)
                    if prefix[:4]!=b'HEK:':continue
                    kinds[prefix[4:].hex()]+=1
                    if name.endswith(('.js','.jsc')):result['encrypted_js']+=1
                    else:result['encrypted_json']+=1
    result['hek']=dict(kinds)
    return result
if __name__=='__main__':
    if len(sys.argv)!=2:raise SystemExit('Usage: python analyze_xapk.py game.xapk')
    print(json.dumps(inspect(sys.argv[1]),indent=2,ensure_ascii=False))
