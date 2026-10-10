# 实测结果（2026-10-10）

第一轮实现完成：固定底座、请求可靠性、服务隔离、确定性故障测试、Echo JMH、固定到达率场景运行器、原始输出和自动汇总。可选 GetUser/DubboAdapter/JFR 不在本轮实现范围内。

## 当前实现的重复 A/B

实测代码提交 `ad91c4f`（标签 `benchmark/final-code-2026-10-10`），6 次场景使用相同客户端/服务端构建哈希，仅切换 shared/isolated 配置。每组预热 3s、健康 5s、过载 15s、恢复 5s，交替执行 3 次，共 29,160 个计划请求；全部实际发出并保留终结状态。每秒采样资源，payload=128B，连接=1，超时=3000ms，最大在途=512。快服务 100 req/s；慢服务健康 20、过载 120 req/s，服务时间 200ms。

共享 16 线程/队列 200；隔离快慢各 8/100。两个独立 JVM、Hessian+GZIP、WARN 日志、JDK 8、每 JVM 最大堆 256MiB、ActiveProcessorCount=4。未做独立 CPU 亲和性/cgroup 分割；ActiveProcessorCount 是 JVM CPU 数量提示，不是 OS CPU 配额。

以下是各次结果先独立计算后的中位数，不是合并样本分位数：

| 指标（过载阶段） | SHARED | ISOLATED |
|---|---:|---:|
| 快服务成功 P95（ms） | 1387.231 | 1.941 |
| 快服务成功 QPS | 67.267 | 100.000 |
| 快服务成功率（%） | 73.333 | 100.000 |
| 慢服务成功 QPS | 79.200 | 39.733 |
| 慢服务成功 P95（ms） | 1581.911 | 2762.755 |
| 慢服务成功率（%） | 72.667 | 38.889 |

快服务成功 P95 的中位数降幅约 99.86%，成功率提高约 26.67 个百分点。不能省略约 26.67% 的共享快服务失败率，只引用成功延迟。慢服务吞吐下降符合静态资源划分的代价：共享可使用更多业务线程，隔离只保留 8 个慢服务线程。本结果证明该混合负载下保护快服务的效果，不证明总体吞吐或生产性能更高。

所有 6 次生成器有效：未发出=0、结果丢失=0、内容错误=0、非预热调度 P99 <=20ms；结束后 pending=0、计时任务=0、客户端项目线程=0。恢复是预定义的连续三个 1s cohort 成功率>=99%、成功 P95<=100ms，计数有 3s 最小观察时间；不能解释为服务必须等待 3s 才能恢复。范围仅限所覆盖实验，不能宣称全部堆对象无泄漏。

[逐次结果、范围与 JMH](measurements/summary.md)、[结构化汇总](measurements/summary.json)。图表：[P95](measurements/p95_ms.svg)、[成功 QPS](measurements/success_qps.svg)、[队列/pending](measurements/queue-pending.svg)、[CPU/堆](measurements/resources.svg)。队列每秒采样可能漏掉瞬态峰值；容量界限由确定性测试直接断言。CPU/队列与客户端指标使用各自测量起点，不能将时间轴当作精确跨进程同步。

## 补充场景与 JMH 矩阵

前一构建 `34209ab` 还完成 14 个场景：3 次交替 A/B、4 连接、快慢角色互换、未过载混合、快服务单独负载，以及 16 个 JMH 配置（SHARED/ISOLATED × 16/64 线程 × 128/1024B × Throughput/SampleTime）。全部功能验证通过、14 个生成器有效，数据单独保留，**不与最终构建混合统计**。该构建随后修正了注册中心中断、适配器取消传播、入口 deadline 和报表兼容。

最终构建又通过 4 个 JMH 健康复测（两模式 × Throughput/SampleTime，16 线程/128B）。每次 1 fork、预热 1×2s、测量 1×3s；脚本确实按每个外层 fork 重启服务端。JMH 每 invocation 是一次完成且内容核对成功的 RPC，不测 Future 提交速度。SampleTime 使用原生采样直方图，Throughput 原生单位 ops/ms 转 QPS 乘 1000。

本轮是短采样初步实测，没有执行默认 30/60/120/60s 场景和 3-fork 长 JMH 矩阵，不能据此给出稳定容量或统计置信结论。完整长采样命令已经实现：`./scripts/validate-benchmarks.sh full`。V1/V2 同一构建公平比较、三次重复和错误率/原始数据已完成；长采样是进一步强化性能证据。

## 正确性与故障

最终完整 reactor 27 个测试通过（JUnit4/5 均执行），Python 汇总口径 3 个测试通过。18 项定向故障/适配器回归重复 3 次，54 个 testcase 全通过，包含 3,000 次 response/timeout/cancel 竞态，各次真实结果分布见 [故障 JSON](measurements/fault-results.json)。每次终结后 pending/timer 无残留。

真实 ZooKeeper 不可达时，原工具吞掉 InterruptedException 导致 close 失败，已修复为关闭未初始化连接、恢复中断并传播异常；独立 JVM 的回归验证超时、关闭和线程退出。原始 ZooKeeper/Spring 示例两个进程均完成 11 次 RPC、断言有效且正常退出。V0 双连接缺陷与最终功能验证日志位于 `docs/validation`。

## 原始资料与复现

[原始归档](measurements/raw-results.tar.gz) 保留两个构建的逐请求 CSV、metrics、客户端资源、原生 JMH JSON、manifest、有效配置、日志以及三次最新故障 XML/日志。归档校验和见 `measurements/raw-results.tar.gz.sha256`。CSV request_id 是唯一 Echo 业务请求 ID；传输层另有内部 UUID，并按 Channel/UUID 匹配响应。原始数据已经清楚区分请求阶段 cohort 成功率和按完成窗口计算的 QPS。

```bash
sha256sum -c docs/measurements/raw-results.tar.gz.sha256
# 从仓库根解压；文件恢复到忽略的 results/ 下
# 请在不存在同名 results/ 目录的检出中解压，保留原有实验数据
 tar -xzf docs/measurements/raw-results.tar.gz
python3 scripts/summarize.py results/final --output results/recomputed-final
python3 scripts/summarize.py results/validated --output results/recomputed-earlier
```

manifest 保存实际项目/基座/参考 SHA、构建哈希、JVM、负载、机器、连接数与命令；重建 JAR 的 ZIP 时间戳可能改变文件哈希，不应篡改已有 manifest。各构建/参数独立汇总，成功、失败延迟分开，不平均每秒 P95。结果同机、短窗口，不代表跨机生产吞吐。
