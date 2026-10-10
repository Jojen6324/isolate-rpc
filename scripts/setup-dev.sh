#!/usr/bin/env bash
set -euo pipefail
TOOLS=${RPC_TOOLCHAINS:-/workspace/toolchains}
mkdir -p "$TOOLS"
python3 - "$TOOLS" <<'PYTHON'
import sys, subprocess, hashlib, re, tarfile
from pathlib import Path
p=Path(sys.argv[1])
artifacts=[('maven','apache-maven-3.9.9','https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz','sha512',''),('jdk8','jdk8u462-b08','https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u462-b08/OpenJDK8U-jdk_x64_linux_hotspot_8u462b08.tar.gz','sha256','.txt'),('zookeeper','apache-zookeeper-3.5.8-bin','https://archive.apache.org/dist/zookeeper/zookeeper-3.5.8/apache-zookeeper-3.5.8-bin.tar.gz','sha512','')]
for name,directory,url,algo,suffix in artifacts:
 archive=p/(name+'.tar.gz'); checksum=p/(name+'.tar.gz.'+algo)
 for dest,source in [(archive,url),(checksum,url+'.'+algo+suffix)]:
  if not dest.exists(): subprocess.run(['curl','-fsSL','--retry','2','-o',str(dest),source],check=True)
 expected=re.search(r'\b[0-9a-fA-F]{%d}\b' % (hashlib.new(algo).digest_size*2),checksum.read_text()).group().lower()
 if hashlib.new(algo,archive.read_bytes()).hexdigest()!=expected: raise SystemExit(name+' checksum mismatch; remove damaged archive and retry')
 if not (p/directory).exists():
  with tarfile.open(archive) as t: t.extractall(p,filter='data')
 print(name+' verified')
(p/'zk-data').mkdir(exist_ok=True)
config=p/'zoo.cfg'
if not config.exists(): config.write_text('tickTime=2000\ndataDir='+str(p/'zk-data')+'\nclientPort=2181\nadmin.enableServer=false\n4lw.commands.whitelist=ruok\n')
PYTHON
"$(dirname "$0")/mvn.sh" -DskipTests install
