#!/usr/bin/env bash
set -euo pipefail
TOOLS=${RPC_TOOLCHAINS:-/workspace/toolchains}
export JAVA_HOME="$TOOLS/jdk8u462-b08"
export ZOO_LOG_DIR="$TOOLS"
exec python3 - "$TOOLS" "${1:-start}" <<'PY'
import os, socket, subprocess, sys, time
from pathlib import Path
root=Path(sys.argv[1]).resolve(); action=sys.argv[2]; config=root/'zoo.cfg'
if action not in ['start','stop','status']:raise SystemExit('Usage: zk.sh start|stop|status')
properties=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line and not line.startswith('#'))
data=Path(properties['dataDir']).resolve(); pidfile=data/'zookeeper_server.pid'
def ready():
 try:
  with socket.create_connection(('127.0.0.1',int(properties.get('clientPort','2181'))),timeout=1) as s:
   s.sendall(b'ruok'); return s.recv(4)==b'imok'
 except OSError:return False
if action=='start' and ready():print('Existing ZooKeeper is ready');raise SystemExit(0)
owned=False
if pidfile.exists():
 try:
  pid=int(pidfile.read_text().strip());os.kill(pid,0)
  command=Path('/proc')/str(pid)/'cmdline'
  value=command.read_bytes()
  owned=b'org.apache.zookeeper.server.quorum.QuorumPeerMain' in value and str(config).encode() in value
 except ProcessLookupError:pass
 except FileNotFoundError:pass
 except ValueError:pass
 if not owned:
  if not data.is_relative_to(root):raise SystemExit('Cannot change PID metadata outside the toolchain directory')
  pidfile.unlink(missing_ok=True);print('Removed stale ZooKeeper PID metadata; no process was signalled')
if action=='stop' and not owned:print('Managed ZooKeeper is not running');raise SystemExit(0)
if not (action=='start' and owned):
 subprocess.run([str(root/'apache-zookeeper-3.5.8-bin/bin/zkServer.sh'),action,str(config)],check=True)
if action=='start':
 for _ in range(100):
  if ready():print('ZooKeeper ready');break
  time.sleep(.1)
 else:raise SystemExit('ZooKeeper functional readiness failed')
PY
