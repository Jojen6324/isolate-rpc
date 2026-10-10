#!/usr/bin/env bash
set -euo pipefail
TOOLS=${RPC_TOOLCHAINS:-/workspace/toolchains}
export JAVA_HOME="$TOOLS/jdk8u462-b08"
export ZOO_LOG_DIR="$TOOLS"
if [[ "${1:-start}" == start ]] && python3 - <<'PYTHON'
import socket
try:
 with socket.create_connection(('127.0.0.1',2181),timeout=1) as s:
  s.sendall(b'ruok')
  raise SystemExit(0 if s.recv(4)==b'imok' else 1)
except OSError: raise SystemExit(1)
PYTHON
then
 echo 'Existing ZooKeeper is ready'
 exit 0
fi
"$TOOLS/apache-zookeeper-3.5.8-bin/bin/zkServer.sh" "${1:-start}" "$TOOLS/zoo.cfg"
if [[ "${1:-start}" == start ]]; then
 python3 - <<'PYTHON'
import socket,time
for _ in range(50):
 try:
  with socket.create_connection(('127.0.0.1',2181),timeout=1) as s:
   s.sendall(b'ruok')
   if s.recv(4)==b'imok': print('ZooKeeper ready'); break
 except OSError: pass
 time.sleep(.1)
else: raise SystemExit('ZooKeeper readiness failed')
PYTHON
fi
