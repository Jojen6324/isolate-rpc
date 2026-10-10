#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
PROFILE=${1:-smoke}
OUT=${2:-results/validation-$(date -u +%Y%m%dT%H%M%S)}
case "$PROFILE" in
 smoke) PHASES=(--warmup-seconds 3 --healthy-seconds 5 --overload-seconds 15 --recovery-seconds 5); JMH=(--forks 1 --warmup-iterations 1 --iterations 1 --warmup-time 2 --measurement-time 3) ;;
 full) PHASES=(--warmup-seconds 30 --healthy-seconds 60 --overload-seconds 120 --recovery-seconds 60); JMH=(--forks 3 --warmup-iterations 3 --iterations 3 --warmup-time 10 --measurement-time 20) ;;
 *) echo 'Usage: validate-benchmarks.sh smoke|full [output-directory]' >&2; exit 2 ;;
esac
[[ ! -e "$OUT" ]] || { echo 'Output already exists' >&2; exit 1; }
mkdir -p "$OUT"
./scripts/mvn.sh -DskipTests install > "$OUT/build.log" 2>&1
for REPEAT in 1 2 3; do
 for MODE in shared isolated; do
  ./scripts/benchmark.sh scenario --mode "$MODE" --no-build --output "$OUT/ab-$MODE-$REPEAT" --fast-rate 100 --slow-rate 120 --healthy-slow-rate 20 "${PHASES[@]}"
 done
done
for MODE in shared isolated; do
 ./scripts/benchmark.sh scenario --mode "$MODE" --no-build --connections 4 --output "$OUT/multi-$MODE" --fast-rate 100 --slow-rate 120 "${PHASES[@]}"
 ./scripts/benchmark.sh scenario --mode "$MODE" --no-build --swap-roles --output "$OUT/swap-$MODE" --fast-rate 100 --slow-rate 120 "${PHASES[@]}"
 ./scripts/benchmark.sh scenario --mode "$MODE" --no-build --scenario healthy --output "$OUT/healthy-$MODE" "${PHASES[@]}"
 ./scripts/benchmark.sh scenario --mode "$MODE" --no-build --scenario fast-only --output "$OUT/fast-only-$MODE" "${PHASES[@]}"
done
for THREADS in 16 64; do
 for PAYLOAD in 128 1024; do
  for METRIC in throughput sample; do
   for MODE in shared isolated; do
    ./scripts/benchmark.sh jmh --mode "$MODE" --no-build --threads "$THREADS" --payload "$PAYLOAD" --metric "$METRIC" --output "$OUT/jmh-$MODE-$THREADS-$PAYLOAD-$METRIC" "${JMH[@]}"
   done
  done
 done
done
python3 scripts/summarize.py "$OUT" --output "$OUT/summary"
