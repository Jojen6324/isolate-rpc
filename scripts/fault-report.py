#!/usr/bin/env python3
"""Export assertions from freshly executed JUnit XML, rather than infer reliability from performance."""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,default=Path('results/fault-results.json'))
parser.add_argument('--repeat',type=int,default=3)
args=parser.parse_args()
if args.repeat<1:parser.error('repeat must be positive')
import subprocess,time
reports=[]
root=Path(__file__).resolve().parents[1]
for run in range(args.repeat):
    before=time.time()
    process=subprocess.run([str(root/'scripts/mvn.sh'),'-pl','rpc-framework-simple,rpc-benchmark-client','-am','-Dtest=RequestLifecycleTest,ServerResponseTest,ServiceIsolationTest,DefaultDiscoveryShutdownTest,GuideRpcAdapterTest','-Dsurefire.failIfNoSpecifiedTests=false','test'],cwd=root,capture_output=True,text=True)
    directory=args.output.parent/f'fault-run-{run+1}'
    directory.mkdir(parents=True,exist_ok=True)
    (directory/'test.log').write_text(process.stdout+process.stderr)
    cases=[]
    for file in sorted(list((root/'rpc-framework-simple/target/surefire-reports').glob('TEST-*.xml'))+list((root/'rpc-benchmark-client/target/surefire-reports').glob('TEST-*.xml'))):
        if file.stat().st_mtime < before:continue
        if not any(name in file.name for name in ['RequestLifecycleTest','ServerResponseTest','ServiceIsolationTest','DefaultDiscoveryShutdownTest','GuideRpcAdapterTest']):continue
        copied=directory/file.name;copied.write_bytes(file.read_bytes())
        for case in ET.parse(file).getroot().findall('testcase'):
            status='failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            cases.append({'class':case.attrib['classname'],'assertion':case.attrib['name'],'seconds':float(case.attrib['time']),'status':status})
    race=re.search(r'RACE_RESULTS (\{[^\n]+\})',process.stdout)
    reports.append({'race_outcomes':json.loads(race.group(1)) if race else None,'run':run+1,'command_exit_code':process.returncode,'response_timeout_cancel_race_requests':1000,'tests':cases,'status':'passed' if process.returncode==0 and cases and all(c['status']=='passed' for c in cases) else 'failed'})
    if reports[-1]['status']!='passed':break
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps({'runs':reports,'scope':'JUnit deterministic fault injection plus real local TCP; each race run covers 1000 requests. Zero pending is not a claim of zero heap leaks.'},indent=2)+'\n')
if len(reports)!=args.repeat or any(r['status']!='passed' for r in reports):raise SystemExit('Fault regression failed; retained logs and current-run XML')
print(f'{args.repeat} fault runs passed; report: {args.output}')
