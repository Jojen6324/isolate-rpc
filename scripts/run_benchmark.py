#!/usr/bin/env python3
"""Run server and client as separate JVMs, retaining commands, raw output and failure evidence."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import subprocess
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
BASE = 'e2f0dd1be07770da08f38d91a8649e4cdf562ac8'
REFERENCE = ['6e6f3132b3a34843b1e7a1baeec2be12c5dfa7cc', '704a4a82240d02fdcf89192c6d185cd07594b9f3']
JAVA = Path(os.environ.get('RPC_TOOLCHAINS', '/workspace/toolchains')) / 'jdk8u462-b08/bin/java'
JVM = ['-XX:ActiveProcessorCount=4', '-Xms256m', '-Xmx256m', '-Dorg.slf4j.simpleLogger.defaultLogLevel=warn']
JAR = ROOT / 'rpc-benchmark-client/target/benchmarks.jar'
SERVER_JAR = ROOT / 'rpc-benchmark-server/target/rpc-benchmark-server-1.0-SNAPSHOT.jar'

def command(args, **kwargs):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True, **kwargs)

def git(*args):
    return command(['git', *args], capture_output=True, text=True).stdout.strip()

def text(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None

def manifest(args):
    model = next((x.split(':', 1)[1].strip() for x in Path('/proc/cpuinfo').read_text().splitlines() if x.startswith('model name')), None)
    sources = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    source_hash = hashlib.sha256()
    for name in sorted(set(sources)):
        if name and (ROOT / name).is_file():
            source_hash.update(name.encode()); source_hash.update((ROOT / name).read_bytes())
    return {'base_commit': BASE, 'project_commit': git('rev-parse', 'HEAD'), 'reference_commits': REFERENCE,
            'benchmark_reference': 'apache/dubbo-benchmark@0641cb0877d11da7e2506205d7174f58e549dcfb',
            'source_tree_sha256': source_hash.hexdigest(), 'working_tree_clean': not bool(git('status', '--porcelain')),
            'artifact_sha256': hashlib.sha256(JAR.read_bytes()).hexdigest(), 'server_artifact_sha256': hashlib.sha256(SERVER_JAR.read_bytes()).hexdigest(), 'parameters': vars(args),
            'jdk': command([JAVA, '-version'], capture_output=True, text=True).stderr.strip(), 'maven': '3.9.9', 'jmh': '1.37',
            'dependencies': {'netty': '4.1.42.Final', 'hessian': '4.0.65', 'curator': '4.2.0', 'lombok': '1.18.8'},
            'serialization': 'Hessian', 'compression': 'GZIP', 'log_level': 'warn', 'server_jvm_args': JVM,
            'client_jvm_args': JVM, 'server_io_threads': {'boss': 1, 'worker': 2}, 'client_io_threads_per_connection': 2,
            'effective_processor_count_per_jvm': 4, 'host_cpu_count': os.cpu_count(), 'machine': platform.platform(), 'cpu_model': model,
            'cpu_limit': text('/sys/fs/cgroup/cpu.max'), 'memory_limit': text('/sys/fs/cgroup/memory.max'),
            'same_host': True, 'created_utc': datetime.now(timezone.utc).isoformat(), 'commands': []}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('kind', choices=['jmh', 'scenario'])
    parser.add_argument('--mode', choices=['shared', 'isolated'], required=True)
    parser.add_argument('--output')
    parser.add_argument('--host', default='127.0.0.1'); parser.add_argument('--port', type=int, default=9998)
    parser.add_argument('--payload', type=int, default=128); parser.add_argument('--connections', type=int, default=1)
    parser.add_argument('--timeout', type=int, default=3000); parser.add_argument('--no-build', action='store_true')
    parser.add_argument('--threads', type=int, default=16); parser.add_argument('--forks', type=int, default=3)
    parser.add_argument('--metric', choices=['throughput', 'sample'], default='throughput')
    parser.add_argument('--warmup-iterations', type=int, default=3); parser.add_argument('--iterations', type=int, default=3)
    parser.add_argument('--warmup-time', type=int, default=10); parser.add_argument('--measurement-time', type=int, default=20)
    parser.add_argument('--scenario', choices=['slow-overload', 'healthy', 'fast-only'], default='slow-overload')
    parser.add_argument('--fast-rate', type=float, default=100); parser.add_argument('--slow-rate', type=float, default=120)
    parser.add_argument('--healthy-slow-rate', type=float, default=20); parser.add_argument('--max-inflight', type=int, default=512)
    parser.add_argument('--warmup-seconds', type=int, default=30); parser.add_argument('--healthy-seconds', type=int, default=60)
    parser.add_argument('--overload-seconds', type=int, default=120); parser.add_argument('--recovery-seconds', type=int, default=60)
    parser.add_argument('--swap-roles', action='store_true')
    args = parser.parse_args()
    if args.forks < 1 or args.threads < 1 or args.iterations < 1 or args.warmup_iterations < 0 or args.warmup_time < 1 or args.measurement_time < 1:
        parser.error('Invalid JMH iteration, fork or thread parameters')
    if not args.no_build:
        command([ROOT / 'scripts/mvn.sh', '-DskipTests', 'install'])
    if not JAR.is_file():
        parser.error('Benchmark JAR missing; build first')
    discovered = command([JAVA, '-jar', JAR, '-l'], capture_output=True, text=True).stdout
    if 'RpcCallBenchmark.echo' not in discovered:
        raise RuntimeError('JMH annotation processing failed')
    output = Path(args.output or f'results/{datetime.now(timezone.utc):%Y%m%dT%H%M%S}-{args.kind}-{args.mode}')
    output.mkdir(parents=True, exist_ok=False)
    output = output.resolve()
    report = manifest(args)
    config = (ROOT / f'configs/{args.mode}.properties').read_text()
    if args.swap_roles:
        config += '\nswap.roles=true\n'
    (output / 'effective.properties').write_text(config)
    report['effective_server_properties'] = config
    if args.kind == 'scenario' and args.scenario in ['healthy', 'fast-only']:
        args.slow_rate = args.healthy_slow_rate if args.scenario == 'healthy' else 0
        if args.scenario == 'fast-only':
            args.healthy_slow_rate = 0
    report['parameters'] = vars(args)
    processes = []
    try:
        for fork in range(args.forks if args.kind == 'jmh' else 1):
            directory = output / f'fork-{fork + 1}' if args.kind == 'jmh' else output
            directory.mkdir(exist_ok=True)
            server_cmd = [JAVA, *JVM, '-cp', str(SERVER_JAR) + os.pathsep + str(JAR), 'github.javaguide.benchmark.server.BenchmarkServer', output / 'effective.properties', directory / 'metrics.csv', args.host, str(args.port)]
            report['commands'].append([str(x) for x in server_cmd])
            with (directory / 'server.log').open('w') as log:
                server = subprocess.Popen([str(x) for x in server_cmd], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                processes.append(server)
                # Readiness requires an actual content-checked RPC, not just a listening port.
                ready_cmd = [JAVA, *JVM, '-cp', JAR, 'github.javaguide.benchmark.adapter.GuideRpcAdapter', args.host, str(args.port)]
                report['commands'].append([str(x) for x in ready_cmd])
                for _ in range(50):
                    if server.poll() is not None:
                        raise RuntimeError(f'Server exited; inspect {directory / "server.log"}')
                    if 'READY ' in (directory / 'server.log').read_text():
                        command(ready_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                        break
                    time.sleep(.1)
                else:
                    raise RuntimeError('Server startup exceeded 5 seconds')
                with (directory / 'client.log').open('w') as client_log:
                    if args.kind == 'jmh':
                        metric = 'thrpt' if args.metric == 'throughput' else 'sample'
                        client_cmd = [JAVA, *JVM, '-jar', JAR, 'RpcCallBenchmark.echo', '-f', '1', '-wi', str(args.warmup_iterations), '-w', f'{args.warmup_time}s', '-i', str(args.iterations), '-r', f'{args.measurement_time}s', '-t', str(args.threads), '-bm', metric, '-p', f'payload={args.payload}', '-p', f'connections={args.connections}', '-p', f'host={args.host}', '-p', f'port={args.port}', '-p', f'timeoutMillis={args.timeout}', '-jvmArgs', ' '.join(JVM), '-rf', 'json', '-rff', directory / 'jmh.json']
                    else:
                        client_cmd = [JAVA, *JVM, '-cp', JAR, 'github.javaguide.benchmark.scenario.ScenarioRunner', '--output', directory, '--host', args.host, '--port', str(args.port), '--connections', str(args.connections), '--payload', str(args.payload), '--timeout', str(args.timeout), '--max-inflight', str(args.max_inflight), '--fast-rate', str(args.fast_rate), '--slow-rate', str(args.slow_rate), '--healthy-slow-rate', str(args.healthy_slow_rate), '--warmup-seconds', str(args.warmup_seconds), '--healthy-seconds', str(args.healthy_seconds), '--overload-seconds', str(args.overload_seconds), '--recovery-seconds', str(args.recovery_seconds), '--swap-roles', str(args.swap_roles).lower()]
                    report['commands'].append([str(x) for x in client_cmd])
                    command(client_cmd, stdout=client_log, stderr=subprocess.STDOUT)
                if args.kind == 'jmh':
                    result = json.loads((directory / 'jmh.json').read_text())
                    if not result or any(not math.isfinite(x['primaryMetric']['score']) for x in result) or '<failure>' in (directory / 'client.log').read_text():
                        raise RuntimeError('JMH produced no valid successful measurements')
                else:
                    stats = json.loads((directory / 'client-statistics.json').read_text())
                    if any(stats[key] != 0 for key in ['pending_end', 'timer_tasks_end', 'writer_dropped', 'client_project_threads_alive']):
                        raise RuntimeError('Scenario cleanup validation failed')
                    report['client_statistics'] = stats
                server.send_signal(signal.SIGTERM)
                server.wait(timeout=12)
                if server.returncode not in [0, -signal.SIGTERM, 128 + signal.SIGTERM]:
                    raise RuntimeError(f'Server shutdown returned {server.returncode}')
                processes.remove(server)
        report['status'] = 'passed'
    except BaseException as error:
        report['status'] = 'failed'; report['error'] = str(error)
        raise
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
                    report['forced_server_kill'] = True
        (output / 'manifest.json').write_text(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + '\n')
        print(f'{report["status"]}: {output}', flush=True)

if __name__ == '__main__':
    main()
