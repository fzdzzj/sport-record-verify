#!/usr/bin/env bash
# GC 停顿统计（稳健版：直接抽取行尾 ms 数值）
for f in docs/perf/data/raw/gc-record-baseline.log docs/perf/data/raw/gc-record-optimized.log; do
  echo "== $f =="
  grep "Pause Young" "$f" | tr -d '\r' | grep -oE "[0-9.]+ms$" | sed 's/ms$//' | awk '{sum+=$1; n++; if($1>mx){mx=$1} if($1>50) out++} END {printf "  Young %d 次, 平均 %.2f ms, 最大 %.2f ms, >50ms 的停顿 %d 次\n", n, (n?sum/n:0), (mx+0), (out+0)}'
  echo "  Full GC: $(grep -c 'Pause Full' "$f") 次"
done
