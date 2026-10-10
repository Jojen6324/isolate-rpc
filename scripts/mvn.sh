#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=${RPC_TOOLCHAINS:-/workspace/toolchains}
export JAVA_HOME="$TOOLS/jdk8u462-b08"
export PATH="$JAVA_HOME/bin:$TOOLS/apache-maven-3.9.9/bin:$PATH"
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
  export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
python3 - "$TOOLS/maven-settings.xml" <<'PYTHON'
import os, sys, urllib.parse, xml.etree.ElementTree as E
s=E.Element('settings',xmlns='http://maven.apache.org/SETTINGS/1.2.0')
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
if u.hostname:
 p=E.SubElement(E.SubElement(s,'proxies'),'proxy')
 for k,v in [('id','cloud'),('active','true'),('protocol','http'),('host',u.hostname),('port',str(u.port or 80)),('nonProxyHosts','localhost|127.*')]: E.SubElement(p,k).text=v
 if u.username or u.password: raise SystemExit('Authenticated proxy: supply secure Maven settings externally')
E.ElementTree(s).write(sys.argv[1],encoding='unicode')
PYTHON
cd "$ROOT"
exec mvn -s "$TOOLS/maven-settings.xml" -B -Dmaven.repo.local="${RPC_MAVEN_REPO:-/workspace/.m2/repository}" "$@"
