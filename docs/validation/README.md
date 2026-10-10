# 原始验证日志

日志采用 gzip 无损归档，内容保持原样，避免把原运行器的空格和大量重复日志加入源码 diff。

```bash
gzip -dc docs/validation/full-tests.log.gz
gzip -dc docs/validation/v0-server.log.gz
gzip -dc docs/validation/registry-shutdown-before-fix.log.gz
```

full-tests：最终 27 个测试；latest-build：最终代码构建；v0-*：原始基座构建、测试发现以及双客户端缺陷；zk-client-a/b：各 11 次正常调用（第一条由 -ea 断言，不打印）；registry-shutdown-before-fix：真实注册中心不可达时的旧中断吞咽；registry-shutdown-regression：独立 JVM 回归通过。

定向回归每次的完整 XML/日志另在 docs/measurements/raw-results.tar.gz 中保留。
