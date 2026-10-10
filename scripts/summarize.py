#!/usr/bin/env python3
"""Summarize raw request cohorts and completion-window throughput without averaging quantiles."""
import argparse
import csv
import json
import math
import os
os.environ.setdefault("MPLCONFIGDIR", "/workspace/.cache/matplotlib")
os.environ.setdefault("XDG_CACHE_HOME", "/workspace/.cache")
from collections import Counter, defaultdict
from pathlib import Path
from statistics import median

PHASES = ['warmup', 'healthy', 'overload', 'recovery']

def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    # Nearest-rank quantile of all successful samples in the selected cohort.
    return values[max(0, math.ceil(len(values) * fraction) - 1)]

def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream))

def summarize_run(directory, manifest):
    if manifest['status'] != 'passed':
        return {'run': str(directory), 'status': 'failed', 'error': manifest.get('error')}
    options = manifest['parameters']
    result = {'run': str(directory), 'status': 'passed', 'kind': options['kind'], 'mode': options['mode'], 'options': options, 'artifact_sha256': manifest['artifact_sha256'] if 'artifact_sha256' in manifest else 'fixture', 'server_artifact_sha256': manifest.get('server_artifact_sha256', 'fixture'), 'cpu_model': manifest.get('cpu_model'), 'cpu_limit': manifest.get('cpu_limit'), 'memory_limit': manifest.get('memory_limit'), 'server_jvm_args': manifest.get('server_jvm_args'), 'effective_server_properties': manifest.get('effective_server_properties', '')}
    if options['kind'] == 'jmh':
        result['measurements'] = []
        for file in sorted(directory.glob('fork-*/jmh.json')):
            for measurement in json.loads(file.read_text()):
                metric = measurement['primaryMetric']
                # One invocation is one content-checked successful RPC.
                data = {'fork': file.parent.name, 'mode': measurement['mode'], 'score': metric['score'], 'unit': metric['scoreUnit'], 'threads': measurement['threads'], 'params': measurement['params'], 'raw_data': metric.get('rawData'), 'raw_data_histogram': metric.get('rawDataHistogram')}
                if metric['scoreUnit'] == 'ops/ms':
                    data['success_qps'] = metric['score'] * 1000
                data['percentiles'] = metric.get('scorePercentiles', {})
                result['measurements'].append(data)
        return result
    statistics = json.loads((directory / 'client-statistics.json').read_text())
    samples = rows(directory / 'scenario.csv')
    seconds = statistics['phase_seconds']
    if len(samples) != statistics['scheduled']:
        raise ValueError(f'{directory}: planned request count differs from retained rows')
    identifiers = [x['request_id'] for x in samples]
    if len(set(identifiers)) != len(identifiers):
        raise ValueError(f'{directory}: duplicate request IDs')
    permitted={'success','overloaded','timeout','transport','business','closed','cancelled','correctness_error','client_not_sent'}
    for sample in samples:
        if sample['status'] not in permitted:raise ValueError('Unknown request status')
        if sample['status']!='client_not_sent' and int(sample['latency_ns'])!=int(sample['completed_ns'])-int(sample['actual_ns']):raise ValueError('Inconsistent request latency')
    statuses = Counter(x['status'] for x in samples)
    starts = [sum(seconds[:i]) * 1_000_000_000 for i in range(4)]
    ends = [starts[i] + seconds[i] * 1_000_000_000 for i in range(4)]
    cohorts = []
    for phase in PHASES:
        index = PHASES.index(phase)
        if not seconds[index]:
            continue
        for service in ['fast', 'slow']:
            cohort = [x for x in samples if x['phase'] == phase and x['service'] == service]
            if not cohort:
                continue
            counts = Counter(x['status'] for x in cohort)
            sent = len(cohort) - counts['client_not_sent']
            successful = [int(x['latency_ns']) / 1_000_000 for x in cohort if x['status'] == 'success']
            failed = [int(x['latency_ns']) / 1_000_000 for x in cohort if x['status'] not in ['success', 'client_not_sent']]
            completed_in_window = sum(x['service'] == service and x['status'] == 'success' and starts[index] <= int(x['completed_ns']) < ends[index] for x in samples)
            cohorts.append({'service': service, 'phase': phase, 'planned': len(cohort), 'sent': sent, 'client_not_sent': counts['client_not_sent'], 'statuses': dict(counts), 'success_rate': counts['success'] / sent if sent else None, 'failure_rate': (sent-counts['success']) / sent if sent else None, 'success_qps': completed_in_window / seconds[index], 'successful_completions_in_window': completed_in_window, 'p95_ms': percentile(successful, .95), 'p99_ms': percentile(successful, .99), 'failure_p95_ms': percentile(failed, .95), 'scheduling_p95_ms': percentile([int(x['scheduling_ns']) / 1_000_000 for x in cohort], .95), 'scheduling_p99_ms': percentile([int(x['scheduling_ns']) / 1_000_000 for x in cohort], .99)})
    result.update({'cohorts': cohorts, 'statuses': dict(statuses), 'pending_end': statistics['pending_end'], 'timer_tasks_end': statistics['timer_tasks_end'], 'project_threads_alive': statistics['client_project_threads_alive'], 'generator_valid': not statuses['client_not_sent'] and not statuses['correctness_error'] and statistics['writer_dropped'] == 0 and all((c['scheduling_p99_ms'] or 0) <= 20 for c in cohorts if c['phase'] != 'warmup')})
    # Recovery threshold is defined before interpreting results: three consecutive 1s windows,
    # >=99% success among fast requests planned in the window and successful P95 <=100ms.
    recovery_start = starts[3]
    streak = 0
    result['recovery_seconds'] = None
    for window in range(seconds[3]):
        cohort = [x for x in samples if x['service'] == 'fast' and recovery_start + window * 1e9 <= int(x['planned_ns']) < recovery_start + (window+1) * 1e9]
        good = [int(x['latency_ns']) / 1e6 for x in cohort if x['status'] == 'success']
        healthy = bool(cohort) and len(good) / len(cohort) >= .99 and (percentile(good, .95) or 0) <= 100
        streak = streak + 1 if healthy else 0
        if streak >= 3:
            result['recovery_seconds'] = window + 1
            break
    metrics = rows(directory / 'metrics.csv')
    result['queue_peaks'] = {service: max((int(x['queued']) for x in metrics if x['service'] == service), default=0) for service in ['fast', 'slow']}
    if any(int(x['queued']) > int(x['queue_capacity']) for x in metrics):
        raise ValueError(f'{directory}: server queue exceeded capacity')
    result['one_second_queue_sampling'] = True
    return result

def number(value, digits=2):
    return 'N/A' if value is None else f'{value:.{digits}f}'

def summarize(directories, output):
    results = [summarize_run(p, json.loads((p / 'manifest.json').read_text())) for p in directories]
    output.mkdir(parents=True, exist_ok=True)
    (output / 'summary.json').write_text(json.dumps(results, indent=2, allow_nan=False) + '\n')
    lines = ['# RPC measurements', '', 'These are same-host measurements. Successful-request latency excludes failed requests; errors and unsent requests are reported separately.', '', 'Success rate follows requests planned in each phase until their final state; QPS counts successful completions whose completion time falls within that phase. Quantiles use nearest rank on raw per-run successful latency samples. No per-second P95 averaging.', '', 'Generator validity requires zero unsent/mismatched/dropped rows and scheduling P99 <=20ms outside warmup. Invalid runs are excluded from A/B conclusions.', '', '| Run | Mode | Service | Phase | Sent | Unsent | Success % | Success QPS | P95 ms | P99 ms | Scheduling P99 ms | Valid |', '|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|']
    for result in results:
        for c in result.get('cohorts', []):
            if c['phase'] == 'warmup':
                continue
            lines.append(f"| {Path(result['run']).name} | {result['mode']} | {c['service']} | {c['phase']} | {c['sent']} | {c['client_not_sent']} | {number(c['success_rate']*100 if c['success_rate'] is not None else None)} | {number(c['success_qps'])} | {number(c['p95_ms'])} | {number(c['p99_ms'])} | {number(c['scheduling_p99_ms'])} | {result['generator_valid']} |")
    # Group ONLY identical load, connections, payload, role allocation and phase durations.
    groups = defaultdict(lambda: defaultdict(list))
    for result in results:
        if not result.get('generator_valid'):
            continue
        o = result['options']
        properties=tuple(sorted(line.strip() for line in result['effective_server_properties'].splitlines() if line.strip() and not line.strip().startswith('mode=')))
        build=(result['artifact_sha256'],result['server_artifact_sha256'],result['cpu_model'],result['cpu_limit'],result['memory_limit'],tuple(result['server_jvm_args'] or []),properties)
        key = build + tuple(o[k] for k in ['fast_rate', 'slow_rate', 'healthy_slow_rate', 'payload', 'connections', 'timeout', 'max_inflight', 'warmup_seconds', 'healthy_seconds', 'overload_seconds', 'recovery_seconds', 'swap_roles', 'scenario'])
        for c in result['cohorts']:
            if c['phase'] == 'overload' and c['service'] == 'fast':
                groups[key][result['mode']].append(c)
    lines += ['', '## Repeated matched A/B (per-run medians and ranges)', '', '| Parameters | Mode | Runs | Success QPS median [min,max] | P95 ms median [min,max] | Success % median |', '|---|---|---:|---|---|---:|']
    for key, modes in groups.items():
        for mode, cohorts in modes.items():
            qps = [c['success_qps'] for c in cohorts]; p95 = [c['p95_ms'] for c in cohorts if c['p95_ms'] is not None]; rate = [c['success_rate']*100 for c in cohorts if c['success_rate'] is not None]
            span = lambda data: f'{median(data):.2f} [{min(data):.2f},{max(data):.2f}]' if data else 'N/A'
            lines.append(f'| {key} | {mode} | {len(cohorts)} | {span(qps)} | {span(p95)} | {number(median(rate) if rate else None)} |')
        if all(len(modes[m]) >= 3 for m in ['shared', 'isolated']):
            v1 = median(c['p95_ms'] for c in modes['shared'] if c['p95_ms'] is not None)
            v2 = median(c['p95_ms'] for c in modes['isolated'] if c['p95_ms'] is not None)
            lines += ['', f'Matched median per-run P95 change: {(v1-v2)/v1*100:.2f}% (not a pooled quantile).']
    lines += ['', '## JMH', '', '| Run | Mode | Metric | Threads | Payload | Score | Unit | Success QPS |', '|---|---|---|---:|---:|---:|---|---:|']
    for r in results:
        for m in r.get('measurements', []):
            lines.append(f"| {Path(r['run']).name}/{m['fork']} | {r['mode']} | {m['mode']} | {m['threads']} | {m['params']['payload']} | {number(m['score'])} | {m['unit']} | {number(m.get('success_qps'))} |")
    lines += ['', 'JMH is closed-loop and SampleTime is sampled; it does not establish fixed-arrival tail latency. Error, overload, timeout or mismatched response fails a normal benchmark.', '', '## Cleanup and recovery', '']
    for r in results:
        if r.get('kind') == 'scenario':
            lines.append(f"- {Path(r['run']).name}: statuses={r['statuses']}, pending={r['pending_end']}, timers={r['timer_tasks_end']}, project threads={r['project_threads_alive']}, sampled queue peaks={r['queue_peaks']}, recovery={r['recovery_seconds']}s (three consecutive 1s cohorts, success >=99%, successful P95 <=100ms).")
        if r['status'] == 'failed':
            lines.append(f"- FAILED {r['run']}: {r['error']}")
    lines += ['', 'One-second queue samples can miss transient peaks. Tests enforce the queue bound directly. Static isolation can leave capacity unused when only one service is busy; this experiment does not imply higher total throughput or production performance.', '']
    (output / 'summary.md').write_text('\n'.join(lines))
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
    except ImportError:
        return
    valid = [r for r in results if r.get('kind') == 'scenario']
    for metric, title in [('p95_ms', 'Fast successful P95 (ms)'), ('success_qps', 'Fast successful completions/s')]:
        fig, ax = plt.subplots(figsize=(10, 5))
        for r in valid:
            c = [c for c in r['cohorts'] if c['service'] == 'fast' and c['phase'] != 'warmup']
            ax.plot([c['phase'] for c in c], [c[metric] for c in c], marker='o', label=Path(r['run']).name)
        ax.set_ylabel(title); ax.legend(fontsize=7); fig.tight_layout(); fig.savefig(output / (metric + '.svg')); plt.close(fig)
    fig, (ax, pending_ax) = plt.subplots(2, 1, figsize=(10, 7))
    for r in valid:
        directory = Path(r['run']); data = rows(directory / 'metrics.csv')
        slow = [x for x in data if x['service'] == 'slow']
        ax.plot([int(x['elapsed_ns'])/1e9 for x in slow], [int(x['queued']) for x in slow], label=directory.name)
        client = rows(directory / 'client-metrics.csv')
        pending_ax.plot([int(x['elapsed_ns'])/1e9 for x in client], [int(x['pending']) for x in client], label=directory.name)
    ax.set_ylabel('Sampled slow pool queue'); pending_ax.set_ylabel('Client pending'); pending_ax.set_xlabel('Seconds from respective JVM measurement start')
    ax.legend(fontsize=7); fig.tight_layout(); fig.savefig(output / 'queue-pending.svg'); plt.close(fig)
    fig, axes = plt.subplots(2, 1, figsize=(10, 7))
    for r in valid:
        data = rows(Path(r['run']) / 'metrics.csv'); data = [x for x in data if x['service'] == 'slow']
        axes[0].plot([int(x['elapsed_ns'])/1e9 for x in data], [int(x['heap_used'])/1024**2 for x in data], label=Path(r['run']).name)
        cpu = [0]+[(int(b['cpu_ns'])-int(a['cpu_ns']))/(int(b['elapsed_ns'])-int(a['elapsed_ns'])) for a,b in zip(data,data[1:])]
        axes[1].plot([int(x['elapsed_ns'])/1e9 for x in data], cpu)
    axes[0].set_ylabel('Server heap MiB'); axes[1].set_ylabel('Server CPU cores used'); axes[1].set_xlabel('Server elapsed seconds');axes[0].legend(fontsize=7)
    fig.tight_layout(); fig.savefig(output/'resources.svg');plt.close(fig)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('roots', nargs='+', type=Path); parser.add_argument('--output', type=Path, default=Path('results/summary'))
    args = parser.parse_args()
    directories = sorted({file.parent for root in args.roots for file in ([root/'manifest.json'] if (root/'manifest.json').is_file() else root.rglob('manifest.json'))})
    if not directories:
        parser.error('No result manifests found')
    summarize(directories, args.output)
