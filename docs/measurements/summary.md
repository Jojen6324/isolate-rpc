# RPC measurements

These are same-host measurements. Successful-request latency excludes failed requests; errors and unsent requests are reported separately.

Success rate follows requests planned in each phase until their final state; QPS counts successful completions whose completion time falls within that phase. Quantiles use nearest rank on raw per-run successful latency samples. No per-second P95 averaging.

Generator validity requires zero unsent/mismatched/dropped rows and scheduling P99 <=20ms outside warmup. Invalid runs are excluded from A/B conclusions.

| Run | Mode | Service | Phase | Sent | Unsent | Success % | Success QPS | P95 ms | P99 ms | Scheduling P99 ms | Valid |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| ab-isolated-1 | isolated | fast | healthy | 500 | 0 | 100.00 | 100.00 | 3.99 | 6.27 | 0.85 | True |
| ab-isolated-1 | isolated | slow | healthy | 100 | 0 | 100.00 | 20.00 | 203.90 | 204.96 | 0.32 | True |
| ab-isolated-1 | isolated | fast | overload | 1500 | 0 | 100.00 | 100.00 | 1.94 | 4.16 | 0.71 | True |
| ab-isolated-1 | isolated | slow | overload | 1800 | 0 | 38.89 | 39.73 | 2760.34 | 2763.30 | 0.79 | True |
| ab-isolated-1 | isolated | fast | recovery | 500 | 0 | 100.00 | 100.00 | 0.87 | 1.03 | 0.17 | True |
| ab-isolated-1 | isolated | slow | recovery | 100 | 0 | 99.00 | 40.00 | 2566.92 | 2605.77 | 0.14 | True |
| ab-isolated-2 | isolated | fast | healthy | 500 | 0 | 100.00 | 100.00 | 2.83 | 5.16 | 0.54 | True |
| ab-isolated-2 | isolated | slow | healthy | 100 | 0 | 100.00 | 20.00 | 203.18 | 204.93 | 0.36 | True |
| ab-isolated-2 | isolated | fast | overload | 1500 | 0 | 100.00 | 100.00 | 2.10 | 4.33 | 0.80 | True |
| ab-isolated-2 | isolated | slow | overload | 1800 | 0 | 38.89 | 39.73 | 2766.01 | 2771.39 | 0.62 | True |
| ab-isolated-2 | isolated | fast | recovery | 500 | 0 | 100.00 | 100.00 | 1.09 | 3.02 | 0.59 | True |
| ab-isolated-2 | isolated | slow | recovery | 100 | 0 | 99.00 | 40.00 | 2565.69 | 2605.57 | 1.29 | True |
| ab-isolated-3 | isolated | fast | healthy | 500 | 0 | 100.00 | 100.00 | 2.53 | 5.23 | 0.30 | True |
| ab-isolated-3 | isolated | slow | healthy | 100 | 0 | 100.00 | 20.00 | 203.29 | 204.57 | 0.35 | True |
| ab-isolated-3 | isolated | fast | overload | 1500 | 0 | 100.00 | 100.00 | 1.53 | 3.74 | 0.31 | True |
| ab-isolated-3 | isolated | slow | overload | 1800 | 0 | 38.89 | 39.73 | 2762.75 | 2764.64 | 0.31 | True |
| ab-isolated-3 | isolated | fast | recovery | 500 | 0 | 100.00 | 100.00 | 1.45 | 2.98 | 0.22 | True |
| ab-isolated-3 | isolated | slow | recovery | 100 | 0 | 99.00 | 40.00 | 2563.35 | 2596.94 | 0.80 | True |
| ab-shared-1 | shared | fast | healthy | 500 | 0 | 100.00 | 100.00 | 2.28 | 3.91 | 0.18 | True |
| ab-shared-1 | shared | slow | healthy | 100 | 0 | 100.00 | 20.00 | 202.82 | 204.29 | 0.16 | True |
| ab-shared-1 | shared | fast | overload | 1500 | 0 | 73.47 | 67.27 | 1387.23 | 1397.69 | 0.27 | True |
| ab-shared-1 | shared | slow | overload | 1800 | 0 | 72.61 | 79.20 | 1581.91 | 1590.99 | 0.30 | True |
| ab-shared-1 | shared | fast | recovery | 500 | 0 | 99.60 | 118.20 | 1136.23 | 1253.21 | 0.19 | True |
| ab-shared-1 | shared | slow | recovery | 100 | 0 | 99.00 | 43.60 | 1321.72 | 1453.23 | 0.17 | True |
| ab-shared-2 | shared | fast | healthy | 500 | 0 | 100.00 | 100.00 | 2.67 | 5.86 | 0.18 | True |
| ab-shared-2 | shared | slow | healthy | 100 | 0 | 100.00 | 20.00 | 203.23 | 205.52 | 0.39 | True |
| ab-shared-2 | shared | fast | overload | 1500 | 0 | 73.27 | 67.13 | 1384.48 | 1391.71 | 0.26 | True |
| ab-shared-2 | shared | slow | overload | 1800 | 0 | 72.67 | 79.20 | 1579.12 | 1587.74 | 0.23 | True |
| ab-shared-2 | shared | fast | recovery | 500 | 0 | 99.60 | 118.00 | 1160.15 | 1261.08 | 0.34 | True |
| ab-shared-2 | shared | slow | recovery | 100 | 0 | 99.00 | 43.80 | 1367.23 | 1465.14 | 0.16 | True |
| ab-shared-3 | shared | fast | healthy | 500 | 0 | 100.00 | 100.00 | 2.37 | 4.09 | 0.21 | True |
| ab-shared-3 | shared | slow | healthy | 100 | 0 | 100.00 | 20.00 | 203.34 | 208.93 | 0.32 | True |
| ab-shared-3 | shared | fast | overload | 1500 | 0 | 73.33 | 67.27 | 1410.94 | 1445.25 | 0.34 | True |
| ab-shared-3 | shared | slow | overload | 1800 | 0 | 72.72 | 79.20 | 1607.25 | 1638.42 | 0.33 | True |
| ab-shared-3 | shared | fast | recovery | 500 | 0 | 99.60 | 117.80 | 1172.08 | 1269.07 | 0.18 | True |
| ab-shared-3 | shared | slow | recovery | 100 | 0 | 99.00 | 44.00 | 1371.64 | 1470.72 | 0.20 | True |

## Repeated matched A/B (per-run medians and ranges)

| Parameters | Mode | Runs | Success QPS median [min,max] | P95 ms median [min,max] | Success % median |
|---|---|---:|---|---|---:|
| build 68360f29; F/S 100/120; conn 1; payload 128; phases (3, 5, 15, 5); swap False; slow-overload | isolated | 3 | 100.00 [100.00,100.00] | 1.94 [1.53,2.10] | 100.00 |
| build 68360f29; F/S 100/120; conn 1; payload 128; phases (3, 5, 15, 5); swap False; slow-overload | shared | 3 | 67.27 [67.13,67.27] | 1387.23 [1384.48,1410.94] | 73.33 |

build 68360f29; F/S 100/120; conn 1; payload 128; phases (3, 5, 15, 5); swap False; slow-overload: matched median per-run P95 change 99.86% (not a pooled quantile).

## JMH

| Run | Mode | Metric | Threads | Payload | Score | Unit | Success QPS |
|---|---|---|---:|---:|---:|---|---:|
| jmh-isolated-sample/fork-1 | isolated | sample | 16 | 128 | 1.31 | ms/op | N/A |
| jmh-isolated-throughput/fork-1 | isolated | thrpt | 16 | 128 | 11.04 | ops/ms | 11041.52 |
| jmh-shared-sample/fork-1 | shared | sample | 16 | 128 | 1.38 | ms/op | N/A |
| jmh-shared-throughput/fork-1 | shared | thrpt | 16 | 128 | 8.72 | ops/ms | 8723.76 |

JMH is closed-loop and SampleTime is sampled; it does not establish fixed-arrival tail latency. Error, overload, timeout or mismatched response fails a normal benchmark.

## Cleanup and recovery

- ab-isolated-1: statuses={'success': 3759, 'overloaded': 1101}, pending=0, timers=0, project threads=0, sampled queue peaks={'fast': 0, 'slow': 100}, recovery=3s (three consecutive 1s cohorts, success >=99%, successful P95 <=100ms).
- ab-isolated-2: statuses={'success': 3759, 'overloaded': 1101}, pending=0, timers=0, project threads=0, sampled queue peaks={'fast': 0, 'slow': 100}, recovery=3s (three consecutive 1s cohorts, success >=99%, successful P95 <=100ms).
- ab-isolated-3: statuses={'success': 3759, 'overloaded': 1101}, pending=0, timers=0, project threads=0, sampled queue peaks={'fast': 0, 'slow': 100}, recovery=3s (three consecutive 1s cohorts, success >=99%, successful P95 <=100ms).
- ab-shared-1: statuses={'success': 3966, 'overloaded': 894}, pending=0, timers=0, project threads=0, sampled queue peaks={'fast': 200, 'slow': 200}, recovery=5s (three consecutive 1s cohorts, success >=99%, successful P95 <=100ms).
- ab-shared-2: statuses={'success': 3964, 'overloaded': 896}, pending=0, timers=0, project threads=0, sampled queue peaks={'fast': 200, 'slow': 200}, recovery=5s (three consecutive 1s cohorts, success >=99%, successful P95 <=100ms).
- ab-shared-3: statuses={'success': 3966, 'overloaded': 894}, pending=0, timers=0, project threads=0, sampled queue peaks={'fast': 198, 'slow': 198}, recovery=5s (three consecutive 1s cohorts, success >=99%, successful P95 <=100ms).

One-second queue samples can miss transient peaks. Tests enforce the queue bound directly. Static isolation can leave capacity unused when only one service is busy; this experiment does not imply higher total throughput or production performance.
