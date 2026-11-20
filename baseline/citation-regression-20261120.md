# 引用 2.0 回归样例（20 条）

目标：回答正文出现 `[n]`，n 必须在 sources 范围内，且至少一个引用命中 expectedDocIds 中的文档。

> **ID 口径修正（20261120 实跑后）**：初版 expectedDocIds 取自样本库，和全量库（4,557 篇）的文档 ID 不一致；本表已按全量库标题/路径重新映射，并把“同一主题的多篇可接受文档”合并为一组。下表是全量库口径，可直接用于复跑。

评测口径：引用协议通过率 ≥ 19/20；不把“回答是否完全正确”混入本回归，生成质量仍由 M2.2 评测 2.0 负责。

| # | 问题 | expectedDocIds | 重点 |
|---|---|---|---|
| 1 | HashMap 的扩容机制是什么？ | 312 | 扩容、负载因子 |
| 2 | JVM 内存区域如何划分？ | 33 | 程序计数器、虚拟机栈、堆 |
| 3 | Spring Boot 自动配置原理？ | 91 | @EnableAutoConfiguration、条件装配 |
| 4 | ArrayList 与 LinkedList 的区别？ | 11 | 底层结构、随机访问 |
| 5 | Promise 的三种状态与链式调用？ | 1106 | pending/fulfilled/rejected |
| 6 | CSS 盒模型有哪几种？ | 855 | content-box、border-box |
| 7 | 浏览器事件循环中宏任务与微任务？ | 1113 | event loop、microtask |
| 8 | 虚拟 DOM 的 diff 策略？ | 1319,1423,1515,1517,1707 | 同层比较、key |
| 9 | 闭包是什么，常见使用场景？ | 1027,1036 | 作用域、内存 |
| 10 | 原型链如何实现继承？ | 1044,1045 | prototype、__proto__ |
| 11 | TypeScript 泛型约束怎么写？ | 1273,1271 | extends、keyof |
| 12 | Flex 布局的常用属性？ | 879,880,881 | flex-direction、justify-content |
| 13 | Grid 布局如何定义行列？ | 882,883,884 | grid-template-columns/rows |
| 14 | HTTP 缓存中的强缓存与协商缓存？ | 1131,1850 | Cache-Control、ETag |
| 15 | 浏览器缓存有哪些存储方式？ | 1132,1131 | Cookie、localStorage、IndexedDB |
| 16 | 防抖与节流的区别及实现？ | 1138,1599 | timer、立即执行 |
| 17 | Webpack 的构建流程？ | 1877,1856,1857,1875,1880,1892 | entry、loader、plugin |
| 18 | Vite 为什么启动快？ | 1907,1912,1897,522 | ESM、esbuild |
| 19 | Vue3 为什么用 Proxy 替代 Object.defineProperty？ | 1422,1433,1440 | 劫持、数组、新增属性 |
| 20 | HTTP 与 HTTPS 的核心区别？ | 1125,2415,3558 | TLS、证书、加密 |

## 判定脚本建议

1. 对每条问题调用 `/api/kb/chat-stream`，收集 `sources` 与 `token` 事件。
2. 解析回答中的 `\[(\d+)\]`；校验每个编号在 sources 的 citationId 范围内。
3. 取被引用 source 的 docId，判断是否落在 expectedDocIds。
4. 输出 `passed/20` 与失败原因（无引用、编号越界、文档不匹配）。
