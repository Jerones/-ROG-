# 基于 RAG 的施工安全规范智能问答系统

> **公开发布版**：仓库中不含真实 API Key、本机 hosts、上传的规范原文与向量库快照。
> 克隆后请先按 [SECURITY.md](SECURITY.md) 配置 `LLM_API_KEY` 再运行。

面向施工现场安全管理场景的本地知识库问答：把施工安全规范（国家标准、行业标准）切分入库，
提问时先检索相关条款、再基于条款生成回答，每条结论都能追溯到「哪份文件、第几条、第几页」的原文。
回答只依据检索到的条款，不依赖模型自身记忆；检索不到就明确拒答，而不是硬编一段像模像样的答案。

## 功能概览

系统只有一个页面，但有**两个菜单**：

### 菜单一：对话
- 聊天问答：输入施工安全相关问题，系统先从知识库检索规范条款，再生成回答；
- **流式输出**（SSE）：回答逐段出现；
- **引用溯源**：每条回答下方有引用卡片，标明来源文档名、条款号、PDF 页码、原文片段和相似度分数；
- 左侧可调参数：召回条数 topK。
- **无召回不编造**：向量相似度全部低于 `min-score` 时，明确回复"知识库中未找到相关规范"，绝不让模型硬编。是否召回**只由向量相似度决定**，BM25 仅对已召回候选加权排序（见下文配置说明里的取舍）。

### 菜单二：知识库
- 文档列表：文件名、大小、切分块数、入库时间、状态，可删除；
- **三种导入入口**：拖拽 / 多选 / 选文件夹，或**直接把条文粘贴进文本框**（Ctrl+V，不用先存成文件）。
  支持 PDF、Word（docx/doc/wps）、TXT、Markdown、PPTX、XLSX、HTML；
- **入库前预检**：选完文件先解析一遍但**不写库**，回报"预计切多少块、条款号覆盖率、要调几次 embedding、
  是否与库里已有文档重复、是不是同一部标准的另一个年号"；确认无误再入库；
- 多个文件排队逐个入库，**每个文件独立进度条**（解析 → 向量化 x/y 块 → 完成），单个文件失败不影响其他文件；
- 重建索引：一键按 `data/docs/` 中的原文件全量重建；
- 索引统计：文档总数、块总数、向量维度、当前 Embedding 模型、向量库实现；
- **检索测试**小工具：输入问题只看召回片段和分数，不生成回答（不消耗生成调用，便于调参对照）。
- **引用可定位到条款**：入库时解析 `第X条` / `3.2.1` / `附录A` 三类编号，引用卡片显示
  "《JGJ80-2016》4.1.1 · 第 47 页"，而不是笼统的"正文片段"。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21（pom 中 `java.version=21`、`maven.compiler.release=21`） |
| 框架 | Spring Boot 3.4.5 + **Spring AI 1.0.0**（spring-ai-bom 统一版本） |
| 构建 | Maven 3.9.12，IntelliJ IDEA 直接打开 pom.xml 导入 |
| 向量库 | Spring AI `VectorStore` 抽象，两套实现可切换：默认 **SimpleVectorStore**（零外部依赖），`chroma` profile 切 **Chroma** |
| 模型 | OpenAI 兼容接口（`spring-ai-starter-model-openai`），默认指向通义千问兼容端点，可换 Kiwi |
| Embedding | 默认 `text-embedding-v3`（通义）；可换任意兼容端点的 bge 系列模型名 |
| 文档解析 | PDFBox（PDF 逐页，保留页码）+ Apache Tika（docx/doc/wps/pptx/xlsx/html） |
| 前端 | 原生 HTML/CSS/JS（Spring Boot 静态托管，无前端框架） |

## 架构与 RAG 链路

```
                        ┌──────────────────── 入库（ETL）────────────────────┐
  PDF/TXT/MD        ──► DocumentReader ──► ClauseTextSplitter ──► VectorStore.add()
  Word/PPT/Excel/HTML   (PagePdf 带页码 /   (自研：条款优先切分，   │  metadata:
                         TextReader /       文本规整 + 编号识别，   │  docName/clause/
                         Tika)              超长走 Token 兜底)      │  chunkIndex/page
                                                                    ▼
                        ┌──────────────────── 检索 ────────────────────────┐
  用户问题 ──► HybridRetriever ──┬─ 向量检索 similaritySearch(topK, 阈值过滤)
                                 │    └─ 阈值过滤后为空 = 无召回（拒答由这一步决定）
                                 ├─ BM25 关键词召回（自研 Bm25Index）
                                 │    └─ 默认只给上面已召回的候选加权，不自己引入新块
                                 └─ 合并去重：0.7×向量分 + 0.3×BM25 归一化分
                                                              │
                        ┌──────────────────── 生成 ────────────────────────┐
                                 └─► ChatService 手写组装 Prompt
                                     （条款按 [1][2] 编号 + 只依据原文回答）
                                     ──► ChatClient（流式）──► 回答 + 引用列表
```

批量上传走的是"落盘即返回、后台排队入库"的独立通道（`IngestJobService`），
与上面这条 ETL 链路共用 `KnowledgeBaseService.ingestSavedFile`，只是多了逐文件进度上报。

对照 Spring AI 的一键方案：`QuestionAnswerAdvisor` 内部做的就是"检索→拼上下文→提问"，
本项目为了把检索、拼接、拒答这三步都放在明面上，在 `ChatService` 里手写了这条链路；
两种写法的对照见 `ChatService` 类注释。

### 核心代码导读

| 文件 | 作用 |
|---|---|
| `etl/ClauseTextSplitter.java` | **自研切分器**：继承 Spring AI `TextSplitter`，文本规整（压空白/接断行/剥页脚水印/过滤目录）→ 按条款边界切分、章标题并入下一条、超长条款用 `TokenTextSplitter` 兜底 |
| `etl/ClauseLabels.java` | 条款号识别：`第X条` / `3.2.1` / `附录A` 三类写法，能把 PDF 拆开的 `8. 2.1` 修回 `8.2.1`，并挡掉参数表里的数字（`1.50 1.8 1.5`）与页码 |
| `etl/DocumentIngestService.java` | ETL：按扩展名选 Reader（PDF 逐页带页码 / TXT / Word 等走 Tika），切分并按顺序回溯补齐 clause |
| `etl/SampleDataInitializer.java` | 首次启动自动导入 `sample_docs/` 示例规范，开箱可问 |
| `rag/Bm25Index.java` | **自研 BM25**：纯 Java 实现（字符 bigram 分词 + Okapi BM25 公式），并附带查询词项覆盖率供录取判定 |
| `rag/HybridRetriever.java` | 混合检索：向量 + BM25 两路召回、各设绝对门槛过滤、加权合并去重 |
| `rag/RetrievedChunk.java` | 检索结果载体（Document + 最终分数） |
| `service/ChatService.java` | 问答主链路：检索 → 无召回拦截 → 手写组装 Prompt → 调用模型 / 抽取式降级 |
| `service/KnowledgeBaseService.java` | 知识库管理：上传/删除/重建/统计/检索测试；`saveUpload` 与 `ingestSavedFile` 拆开，供后台任务复用 |
| `service/IngestJobService.java` | 批量入库任务：落盘即返回 taskId，单线程顺序入库并逐块上报进度，单文件失败不影响其余 |
| `service/DocumentRegistry.java` | 文档注册表（`data/registry.json`）：补齐 VectorStore 抽象没有的"文档级管理"能力 |
| `config/DemoModeConfig.java` | 演示模式：本地兜底 EmbeddingModel（字符 n-gram 哈希向量），无 key 可跑 |
| `config/VectorStoreConfig.java` | 默认 VectorStore：SimpleVectorStore Bean + 本地 JSON 持久化（`@Profile("!chroma")`） |
| `config/CachingEmbeddingModel.java` | **Embedding 批量预取装饰器**：把 SimpleVectorStore 的逐块调用合并成 5 条/请求，失败自动拆小重试（见下节） |
| `config/HttpClientConfig.java` | 把流式用的 WebClient 换成 JDK HttpClient 传输层，与非流式保持一致（见"卡住 19 秒"一节） |
| `tools/pin_dashscope_ip.py` | 逐个探测百炼的 A 记录，挑出"上行大包能通"的 IP 写入 `hosts`，绕开 PMTU 黑洞 |
| `config/ChromaVectorStoreConfig.java` | `chroma` profile 的 Chroma 装配：自己建 tenant/database/collection，绕开 Spring AI 的错误文案判断 |
| `tools/chroma_selfcheck.py` | Chroma 服务端自检：模拟 600 块分批写入，判断这台机器的 Chroma 扛不扛得住（只用标准库） |
| `web/ChatController.java` | `/api/chat`、`/api/chat/stream`（SSE：token 事件 + citations 事件） |
| `web/KnowledgeBaseController.java` | `/api/kb/documents`、`/api/kb/preview`（入库预检）、`/api/kb/uploads`（批量上传 + 进度查询）、`/api/kb/paste`（粘贴入库）、`/api/kb/reindex`、`/api/kb/stats`、`/api/kb/search-test` |
| `web/ApiExceptionHandler.java` | 全局异常映射：400/404/409/413/500 与一句可读文案，替代"一律 500 + 堆栈" |
| `service/IngestPreviewService.java` | 入库预检：解析切分但不写库，算块数/条款率/重复/版本冲突 |
| `util/StandardCode.java` | 从文件名抽标准编号（`JGJ 80-2016`），用于新旧版本提醒 |

## 入库链路：从"一次请求"到"后台作业"

| 接口 | 用途 |
|---|---|
| `POST /api/kb/preview`（字段 `files`，可重复） | 入库预检：解析、切分、统计，**不写库**；返回块数、条款号覆盖率、预计 embedding 调用次数、重复文档名、版本提醒、前 3 块样本 |
| `POST /api/kb/paste`（JSON `{title, text}`） | 粘贴入库：把一段条文落成 txt 后走与上传完全相同的链路，立刻返回 `jobId` |
| `POST /api/kb/documents`（字段 `file`） | 单文件同步入库，兼容脚本/命令行调用 |
| `POST /api/kb/uploads`（字段 `files`，可重复） | 批量上传：**只落盘**，立刻返回 `jobId` 与每文件初始状态 |
| `GET /api/kb/uploads/{jobId}` | 轮询进度：`stage`（排队中/解析切分/向量化/已完成/失败）、`written`/`total`（块数）、`message`（失败原因） |

设计取舍：

- **为什么先预检再入库**：解析几秒、写 300 条向量几十秒还要花配额，代价不对称。
  预检把"会切多少块 / 条款号能不能认出来 / 是不是扫描件 / 和库里哪篇重复 / 是不是旧版标准"
  提前摆出来，避免"等了半分钟才发现传的是封面页或者已经有的文件"。
- **为什么粘贴也要落成文件**：重建索引只扫 `data/docs/`，粘贴内容若只存在内存，
  一次"重建索引"就凭空消失；落成 txt 之后粘贴与上传在后续链路里完全同构。
- **为什么单线程**：embedding 接口有并发/配额限制，多线程反而更容易 429；
  入库本身不追求并行（用户要看到的是逐个完成）。
- **为什么落盘要同步做**：先落盘才有 `data/docs` 里的原文件，重建索引才有数据来源；
  落盘失败（类型不支持）的文件直接标 `FAILED`，不会拖垮整批。

## 为什么大文档会"入库失败：Connection reset"

这是本项目**实测踩到并修掉**的一个真实缺陷。

**现象**：上传一篇 171 块的规范（GB50656），几十秒后报 `失败：Connection reset`，
重传还是失败；重建索引要跑三分半。而同样环境下直接 `curl` 调 embedding 接口是 1 秒内成功的。

**根因有两层**：

1. Spring AI 1.0.0 的 `SimpleVectorStore.add()` 内部是**对每个 Document 单独调一次**
   `embeddingModel.embed(document)`（反编译其 `doAdd` 可见，日志里
   `Calling EmbeddingModel for document id = ...` 一块一行就是逐条调用留下的）。
   一篇 171 块的规范 = **171 次 HTTPS**，1221 块的语料 = **1221 次**。
   调用次数越多，"某一次被中间设备掐断"的概率越高；而任何一次失败都会让整批回滚重来。
2. 批量越大，响应体越大，越容易在传输中途被重置。实测（同一网络、同一 key）：

| 单次请求条数 | 响应体 | 结果 |
|---|---|---|
| 1 条 | 21 KB | 10/10 成功，每次 0.25~0.33 秒 |
| 5 条 | 108 KB | 6/6 成功，每次 0.4~0.5 秒 |
| 10 条（接口上限） | 217 KB | 3 次里 2 次在 **19 秒**后 `Connection reset` |

**解法**：`config/CachingEmbeddingModel.java` 装饰 EmbeddingModel——
入库前先按 **5 条/批**把整篇文本批量算好放进 LRU 缓存，随后 SimpleVectorStore
逐块调用时全部命中缓存，不再产生网络请求；某一批连续失败时自动**二分拆小**
（5 → 2+3 → 1）再试，最终落到"单条"这个稳定档位。同时 `spring.ai.retry`
配了 3 次尝试（1s/2s 退避），兜住偶发的单次抖动。

| 指标 | 改造前 | 改造后 |
|---|---|---|
| 171 块的 PDF | 171 次请求，**失败** | 35 次请求，12 秒入库成功 |
| 重建索引（1392 块 / 5 篇） | 193 秒（实测 3 分 13 秒 @1221 块） | **66 秒** |
| 失败时的行为 | 删掉源文件，用户得重传 | 保留源文件，点「重建索引」即可补录 |

顺带把失败文案改成可操作的：不再只显示 `Connection reset`，而是
"这是调用 Embedding 接口时的网络故障……源文件已保留，稍后点『重建索引』即可补录"。

## 为什么对话会被卡住 19 秒然后降级成抽取式

**现象**：页面提问后转圈约 20 秒，最后显示【抽取式回答】，服务端日志是
`Connection reset`。而同一时刻用 curl 直连百炼只要 0.5 秒。更迷惑的是"时好时坏"：
同一个问题、同一段代码，改天再问又正常了。

**排查过程**（每一步都做了对照，最后才找到真凶）：

| 观察 | 结论 |
|---|---|
| curl 直连 3/3 成功、0.5 秒；Java 独立程序也 3/3 成功 | 不是 key、不是断网 |
| 独立 JVM 里跑 Apache HttpClient5，1.5 秒成功 | 不是客户端库的锅 |
| 去掉 `http_proxy/https_proxy` 再启动，仍 19 秒 reset | 不是代理环境变量 |
| JDK 客户端强制 HTTP/1.1、加 `Expect: 100-continue`，仍 8/8 失败 | 不是 HTTP 版本、不是 100-continue |
| 同一份 4KB 请求体：curl `--resolve` 逐个 IP 打 | **4 个 IP 里只有 1 个通** |

逐个 IP 的实测结果（同一时刻、同一份请求体）：

| dashscope.aliyuncs.com 的 A 记录 | 轻量 GET `/models` | 带 4KB body 的 POST |
|---|---|---|
| 8.140.217.18 | 401 · 0.11s | **401 · 2.6s** |
| 39.96.198.249 | 401 · 0.14s | 12s+ 无响应，随后连接被重置 |
| 8.152.159.24 | 401 · 0.13s | 12s+ 无响应，随后被重置 |
| 39.96.213.166 | 401 · 0.12s | 12s+ 无响应，随后被重置 |

**根因**：TCP 连得上、TLS 握手正常、小请求也正常，只有**上行请求体超过约 1.2KB**
的请求丢包，然后被重置——典型的 **PMTU 黑洞**（路径 MTU 变小，ICMP
"fragmentation needed" 又被中间设备吞掉，大包就静默消失）。而
`dashscope.aliyuncs.com` 有 4 个 A 记录轮询，等于每次请求在抽签：抽到坏 IP 就卡
19 秒再失败。这也解释了为什么"小问题能答、召回 5 条就挂"——不是 topK 的问题，
是请求体跨过了那个临界值。

**解法（两步）**：

1. **固定接入 IP**：`tools/pin_dashscope_ip.py` 逐个 IP 发一个约 2KB 的无鉴权
   POST（服务端立刻回 401，不需要 API key），谁能快速回应就选谁，写进 `hosts`；
   启动时加 `-Djdk.net.hosts.file=<项目>/hosts`，JVM 就只认这个 IP。
   只影响本进程，不动系统 hosts 表，也不影响浏览器和 curl。
   `restart.sh` 会自动带上这个参数，换网络后重新选路用 `PIN_IP=1 bash restart.sh`。
2. **把 HTTP 客户端统一到 JDK 的 `java.net.http.HttpClient`**（顺手做的一致性改动）：
   非流式用 `spring.http.client.factory: jdk`，流式在 `config/HttpClientConfig.java`
   里用 `WebClientCustomizer` + `JdkClientHttpConnector` 换传输层。它不影响能不能通，
   但连接与超时行为更可预测（connect 10s / read 120s）。

**改完实测**（就是截图里那条"施工现场临时用电有什么要求"）：

| 场景 | 改造前 | 改造后 |
|---|---|---|
| topK=5 的流式问答连测 5 次 | 5/5 全部降级成抽取式，每次 40 秒 | **5/5 模型生成**，9~13 秒 |
| topK=10 | 必挂 | 模型生成，20 秒（回答更长） |
| 日志里的降级/重置次数 | 每次都有 | **0** |

另外流式链路还加了一道保险：流式失败且尚未吐出任何 token 时，先换非流式
`call()` 补答一次（不同连接池），补到就逐块推给前端，效果与流式一致；
只有它也不通才退回抽取式。**这里是三步兜底**：原地重试一次 → 换链路补答 → 抽取式。

> 如果你把项目拷到别的机器上，那边网络没有 PMTU 问题，`hosts` 文件可以直接删掉，
> 或者跑一次 `python tools/pin_dashscope_ip.py` 重新选路。

## 写操作互斥与失败处理

这三件事都会同时改"注册表 + 向量库"，所以必须串起来做：

| 操作 | 冲突场景 | 现在的行为 |
|---|---|---|
| 入库（单文件 / 批量 / 粘贴） | 正在重建索引 | 排队等待，日志打一行"正在等待其他写操作完成" |
| 重建索引 | 有文件正在入库 | 排队等待当前文件写完再开始 |
| 删除文档 | 上面任一操作进行中 | 排队等待 |

改动前没有互斥：一边批量上传一边点重建索引，重建会把刚写进去的块当旧块删掉，
上传线程随后又把注册表写回去，向量库与注册表从此对不上——之后每次重建都在收拾上一次的残局。
实测（重建 3 分 13 秒期间发起一次上传）：上传等了 3 分 02 秒后正常完成，数据一致。

另外两处容错：

- **重建按篇兜底**：单篇解析失败只记进 `failed`，其余照常入库，不再"重建到一半停下、
  旧向量已删新向量没建完"。返回体：`{"reindexedDocuments":4,"skippedDuplicates":0,"failed":[{"name":"x.pdf","reason":"…"}]}`。
- **注册表损坏不自毁**：`registry.json` 解析失败时先改名成 `registry.json.corrupt-<时间戳>` 留档再以空表启动。
  以前是直接当空表跑，下一次入库落盘就把坏文件覆盖了，全库块 id 永久丢失（向量还在库里，但没人认识它们）。

## 统一错误响应

`web/ApiExceptionHandler` 把异常翻译成前端能直接显示的一句话：

| 情况 | 状态码 | 响应 |
|---|---|---|
| 类型不支持、内容为空、粘贴超长 | 400 | `{"status":400,"message":"不支持的文件类型…"}` |
| 写冲突（注册表只读保护等） | 409 | `{"status":409,"message":"…"}` |
| 文件超过 50MB 上限 | 413 | `{"status":413,"message":"文件超过大小上限…"}` |
| 任务号不存在、文档不存在 | 404 | 原业务提示 |
| 真正未知的异常 | 500 | 记堆栈日志，返回简短原因 |

改动前这些情况一律 500 + 堆栈，前端只能显示"入库失败：HTTP 500"。

## 条款结构化：让引用卡片说得出"第几条"

改造前后（同一批 3 篇 PDF + 1 篇示例 txt，重建索引实测）：

| 指标 | 改造前 | 改造后 |
|---|---|---|
| 总块数 | 586 | 1221（按条款切分，粒度更细） |
| `clause` 为"正文片段" | 558 块（95%） | 383 块（31%） |
| 能定位到具体条款 | 28 块（5%） | 838 块（69%） |
| 重建耗时 | 1 分 47 秒 | 3 分 28 秒（块数变多） |

关键处理（都在 `ClauseTextSplitter` + `ClauseLabels`）：

1. **文本规整**：压掉 PDF 行尾几十个空格、把被换行切断的句子接回去，
   否则"行首锚点"根本匹配不到条款号；
2. **编号修复**：`8. 2.1` / `2.1. 7` / `第 4 章` 这类被排版拆开的编号先压紧；
3. **三类边界**：`第X条`、`3.2.1`（数字编号）、`附录A`，四级以上数字编号才算边界，避免把页码当条款；
4. **挡掉表格数字**：`1.50 1.8 1.5 1.5` 这种参数表行形状像编号，但紧跟其后十几个字符
   没有汉字，判为表格而不是条款；
5. **去掉版面垃圾**：重复 ≥5 次的短行（页眉页脚水印）、省略号密集的目录行；
6. **顺序回溯**：超长条款被兜底切成多块时，只有第一块带编号，后面几块沿用上一条
   （最多回溯 3 块）；但**只有"块首就是编号"的强证据才向后传**，正文中间蹭到的编号
   不传——否则评分表里"……悬挑长度的 1.25 倍"会把后面几行表格全标成 1.25。

> 剩余 31% 没有条款号的主要是 JGJ59《建筑施工安全检查标准》的评分表——
> 表格行本身没有条款号（"扣 5 分""扣 10 分"），这类块靠"页码 + 文档名"定位，符合预期。
> 这是启发式规则的固有上限：要再提高就只能上版面分析（区分正文/表格区域），
> 属于另一个工程量级。

## 运行步骤（IntelliJ IDEA）

环境要求：**JDK 21**、**Maven 3.9.12**（IDEA 设置里把 Maven home 指到 3.9.12、JDK 指到 21 即可）。

1. **导入工程**：IDEA → Open → 选择本目录的 `pom.xml` → 以 Maven 工程导入，等待依赖下载完成。
2. **向量库不用管**：默认用的是 `SimpleVectorStore`（内存 + `data/simple-vector-store.json` 持久化），
   **不需要 Docker、不需要 Chroma、不需要任何数据库**，装完依赖就能跑通全链路。
   - 想换成真实的 Chroma：按下方「想用 Chroma 怎么办」把服务起起来，再给运行配置加
     Program arguments `--spring.profiles.active=chroma`。
3. **配置模型**：默认 `app.demo-mode=false`，即走真实模型，需要可用的 `LLM_API_KEY`。
   手上暂时没有 key 时，给运行配置加上环境变量 `APP_DEMO_MODE=true` 即可切到离线演示模式（见文末「演示模式说明」）。接真实模型时，二选一：
   - 改 `src/main/resources/application.yml` 里的 `spring.ai.openai.*` 与 `app.demo-mode: false`；
   - 或在 IDEA 运行配置的 Environment variables 里填：
     `LLM_API_KEY=你的key;APP_DEMO_MODE=false`（可另加 `LLM_BASE_URL`、`LLM_CHAT_MODEL`、`LLM_EMBEDDING_MODEL` 覆盖端点与模型名）。
4. **运行**：打开 `SafetyRagApplication`，点 main 方法旁的运行按钮。
   命令行等价写法：`mvn spring-boot:run`（后端在本机跑时记得加 `--server.port=8080` 之类的显式端口）。
5. **访问**：浏览器打开 http://localhost:8080 ，左侧切换「对话 / 知识库」两个菜单。
   首次启动会自动把 `sample_docs/` 里的示例规范入库，直接就能提问。

> 启动日志里会打印一行 `当前 VectorStore 实现：SimpleVectorStore ...`，
> 也可以在知识库菜单的「索引统计」里看 `vectorStore` 字段，确认当前用的是哪套实现。

### 改完代码怎么重启（`restart.sh`）

Windows 上有个坑：服务在跑的时候直接 `mvn package`，jar 被占用会让 `repackage` 失败，
而且会把 fat jar 覆盖成一个一百多 KB 的薄 jar，正在跑的服务当场崩溃（页面报 500）。
`restart.sh` 把顺序固定成「停服 → 打包 → 启动 → 等就绪」：

```bash
bash restart.sh              # 默认端口 18080
PORT=8080 bash restart.sh    # 换端口
SKIP_BUILD=1 bash restart.sh # 只重启不打包
NO_TEST=0 bash restart.sh    # 打包时顺带跑全量测试
```

脚本会做三件额外的事：按端口反查并杀掉旧进程（Git Bash 的 `$!` 拿的是 MSYS 虚拟 pid，
`taskkill` 认不了，所以统一走端口）、识别「薄 jar」并直接报错、轮询 `/api/kb/stats`
确认真的起来了才返回。日志固定写到 `.run.log`。

### 想用 Chroma 怎么办（没有 Docker 也能上）

先说结论：**装不装 Chroma 不影响功能演示**。默认的 SimpleVectorStore 也是真向量检索
（全量余弦相似度），本项目 600 块规模下单次检索在毫秒级；换 Chroma 的收益是
"带 ANN 索引的独立向量服务"这个架构形态，不是性能必需。

硬要用 Chroma，先花 1 分钟确认**这台机器扛不扛得住**：

```bash
python tools/chroma_selfcheck.py          # 模拟 600 块 × 1024 维分批写入
python tools/chroma_selfcheck.py --ghost  # 再测"删除不存在的 id"
```

脚本只依赖标准库，连 `localhost:8000`，任何方式起的 Chroma 都能测。通过了再往下走。

#### 路径 A：WSL2 里跑（无 Docker，成功率最高，推荐）

Windows 版 `chroma-hnswlib` 原生库在部分 Python 版本下会段错误（见下方实测记录），
Linux 版轮子稳定，所以给 Chroma 一个 Linux 运行环境最省事：

```powershell
wsl --install -d Ubuntu      # 已装过可跳过，装完需重启
```

```bash
# 以下在 Ubuntu 终端里执行
sudo apt update && sudo apt install -y python3-pip
pip install "chromadb==0.5.23"
chroma run --path /home/$USER/chroma-data --host 0.0.0.0 --port 8000
```

WSL2 会把端口转发到 Windows 的 localhost，所以**应用侧不用改任何配置**：
加 `--spring.profiles.active=chroma` 就能连上。

- 必须 `--host 0.0.0.0`（不是 `localhost`），否则 Windows 侧连不进来；
- 数据放 WSL 的 `/home/...` 下，别放 `/mnt/c`（跨文件系统 IO 很慢）。

#### 路径 B：换个 Python 版本重装（最快验证，约 10 分钟）

本机目前只有 Python 3.9，而崩溃正是 3.9 的 `chroma-hnswlib` 轮子。装个 3.11 再试，
可能直接就正常了：

1. 从 python.org 安装 Python 3.11（勾选 "Add python.exe to PATH"）；
2. `py -3.11 -m venv .venv-chroma`，再 `.venv-chroma\Scripts\activate`；
3. `pip install "chromadb==0.5.23"`；
4. `chroma run --path ./data/chroma-server --host localhost --port 8000`；
5. 跑 `python tools/chroma_selfcheck.py` 验证。

通过就用它；不通过说明与 Python 版本无关，走路径 A 或 C。

#### 路径 C：Docker Desktop

装 Docker Desktop（会顺带启用 WSL2 后端），在工程目录执行 `docker compose up -d`。
工程自带的 `docker-compose.yml` 已锁 `chromadb/chroma:0.5.23`（Linux 构建）。
这是最"标准"的做法，代价是装 Docker 和占内存。

#### 实测记录：Windows + pip 版 Chroma 的坑

本机（Windows + Python 3.9 + chromadb 0.5.23）复现过的现象：

| 操作 | 结果 |
|---|---|
| 10 条一批 upsert，总量 < 100 | ✅ 200 正常 |
| 删除**存在的** id | ✅ 正常 |
| 删除**不存在的** id | ❌ 服务端 exit 139（段错误），进程消失 |
| 连续写到一百多块（HNSW 扩容） | ❌ 同样 exit 139 |
| 直接调 `chroma-hnswlib.add_items`（dim=4、2 条） | ❌ 也崩 → 原生轮子本身有问题 |

把 numpy 降到 1.26 无效；0.5.23 与 0.6.3 都能复现。客户端只能看到
`Connection reset` / `Connection refused`，服务端日志没有堆栈，很容易误判成应用 bug。

工程侧已做两处防御：自己建集合（不依赖 Spring AI 的错误文案匹配）、删除前先核对
**真实存在的 id** 再删。但**扩容崩溃在应用侧绕不过去**，所以数据量上百块时必须用路径 A/B/C。

> 版本要点：**同一个 `data/chroma-server` 目录不要被不同版本的 Chroma 交替打开**。
> 0.6.x 读 0.5.x 写的 HNSW 段文件，写入时会段错误（已实测）。换版本就换目录，或清空重建。

### 两种向量库实现怎么切换

| | 默认（不加 profile） | `chroma` profile |
|---|---|---|
| 实现 | `SimpleVectorStore` | `Chroma` |
| 依赖 | 无 | 需 Chroma 服务在 8000 |
| 持久化 | `data/simple-vector-store.json` | Chroma 自己的存储目录 |
| 交换 | `@Profile("!chroma")` | `@Profile("chroma")` |
| 启动 | `mvn spring-boot:run` | `mvn spring-boot:run -Dspring-boot.run.profiles=chroma` |

业务代码只依赖 Spring AI 的 `VectorStore` 接口，换实现不动一行业务逻辑——
这也是抽象出这一层接口的实际价值。两个 @Profile 互斥，不会同时出现两个 Bean。

### 演示模式说明（无 API key 时）

`application.yml` 中 `app.demo-mode` 的默认值是 **`false`**（走真实模型）。没有可用 key 时，
用环境变量 `APP_DEMO_MODE=true` 或启动参数 `--app.demo-mode=true` 打开演示模式，此时：
- Embedding 用本地兜底实现 `HashNgramEmbeddingModel`（字符 n-gram 哈希 + 余弦相似度），不调任何外部接口；
- 回答用**抽取式**：直接按相关度摘录召回条款原文并标注出处，页面上会显示"演示模式"角标；
- 全链路（上传→切分→入库→混合检索→引用溯源）都真实运行，只有"模型生成"这一步被摘录代替。
- **能力边界（务必知道）**：在真实语料上实测，哈希向量与问题的余弦相似度只有 **0.02~0.11**，
  全部低于默认 `min-score=0.2`，因此演示模式下向量路基本召回不到东西，实际靠 BM25 兜底。
  这也意味着**演示模式下"无召回不编造"不可靠**：BM25 只认字面，问"今天食堂吃什么"仍会
  命中食堂相关条款。演示模式用于展示链路能跑通，效果与拒答行为请以真实模型为准。
- 实际使用建议配一个真实 key 并关闭演示模式。

### 上游故障为什么不会变成 500

真实模式下模型接口偶发 `Connection reset`：百炼会主动关闭闲置的 keep-alive 连接，
JVM 的 HTTP 连接池复用到这条已经失效的连接时就报这个错；而流式接口的异常发生在
**订阅之后**，`try/catch` 根本接不住——这是曾经"对话请求失败 500"的真实原因。

`ChatService.streamTokens` 因此做了三层处理：

1. **连接期失败自动重试**：还没吐出任何 token、且距发起不到 8 秒时重试一次（新连接基本能成）；
2. **重试仍失败则降级**：改用抽取式回答，SSE 照常推完，`citations` / `done` 事件照常下发；
3. **生成中途断开**：在已输出内容后追加"模型连接中断"提示，再串上召回到的条款原文。

于是前端只会感到"这次回答质量差了点"，不会看到 500；日志里对应两条 WARN：
`模型流式连接失败，重试一次` 与 `模型流式输出中断（已输出=...）`。

## 配置说明（application.yml 关键项）

| 配置 | 说明 |
|---|---|
| `spring.ai.openai.base-url` | OpenAI 兼容端点：通义 `https://dashscope.aliyuncs.com/compatible-mode`；Kiwi `https://api.Kiwi.com` |
| `spring.ai.openai.api-key` | 模型 API key（环境变量 `LLM_API_KEY`） |
| `spring.ai.openai.chat.options.model` | 聊天模型，默认值见 `application.yml`（可用 `LLM_CHAT_MODEL` 覆盖）；须填服务端真实支持的模型名 |
| `spring.ai.openai.embedding.options.model` | 向量模型，默认 `text-embedding-v3`（1024 维）；换 bge 系列时改这里的模型名即可（如服务端部署的 `bge-large-zh-v1.5`） |
| `spring.ai.vectorstore.chroma.*` | 仅 `chroma` profile 生效（见 `application-chroma.yml`）：地址端口、tenant/database、集合名 |
| `app.demo-mode` | 演示模式开关（环境变量 `APP_DEMO_MODE`），默认 `false` |
| `app.chunking.chunk-size / overlap` | 切分参数：单块最大字符数 / 预留重叠量 |
| `app.retrieval.min-score` | **向量路**的最低相似度，低于它视为无召回（防编造的第一道门槛） |
| `app.retrieval.hybrid-enabled` | 是否开启 BM25 混合召回 |
| `app.retrieval.vector-weight / bm25-weight` | 混合合并权重，默认 0.7 / 0.3 |
| `app.retrieval.min-bm25-score` | **BM25 路**的原始分下限（默认 1.0），低于它的块不参与加权，挡掉只蹭到零星字词的低分块 |
| `app.retrieval.bm25-introduce-new` | 是否允许 BM25 单独引入向量路未召回的块，默认 `false`；`true` 回到"两路各自取候选"的经典做法（仅用于对照实验） |

> **为什么默认不让 BM25 单独召回**：BM25 只认字面、不认语义，问题里只要有一个字
> 出现在块里它就能给出正分，而归一化后必然有 top1（=1.0），混合分恒为 0.3 > 0。
> 结果是任何闲聊问题都能凑出几条条款，"无召回不编造"成了死代码——实测问
> "今天食堂吃什么"能命中 5 条食堂相关条款。所以默认把 BM25 限定为"给向量路
> 已召回的候选加权排序"：**有没有召回由向量相似度决定，BM25 只决定排序**。

## 数据目录

```
data/
├── docs/            上传的原始文档（重建索引的数据来源），文件名为 docId__原名
├── registry.json    文档注册表（文档与块的元信息、块原文）
├── simple-vector-store.json   默认实现：SimpleVectorStore 的持久化向量
├── simple-vector-store.model  这份向量是哪个 Embedding 模型生成的（换模型后启动会提示重建索引）
└── chroma-server/   仅 chroma profile 时的 Chroma 数据目录（可选）
sample_docs/         随工程自带的示例规范（自拟文本，非正式规范原文）
```

> **换了 Embedding 模型（或开关演示模式）一定要"重建索引"**：向量库里存的是旧模型算出的向量，
> 新旧向量维度可能一样（都是 1024），混在一起不会报错，但余弦相似度不在同一量纲上——
> 真实语义向量（0.5 上下）会把哈希兜底向量（0.0x）整体压死，表现为"老文档怎么都搜不到"。
> 应用检测到 `simple-vector-store.model` 与当前模型不一致时会在启动日志里明确提示。

## 可选的对照实验

1. **切分参数对比**：固定问题集（自拟 15~20 个问题并人工标注应召回的条款），对比 `chunk-size` 取 200 / 500 / 1000 时的召回命中率（Hit@5），说明"块太小语义不完整、太大噪声多"的权衡；再对比"条款切分 vs 纯 TokenTextSplitter 切分"。
2. **混合检索 vs 纯向量**：关掉 `hybrid-enabled` 跑一遍、打开再跑一遍，对比 Hit@5 与 MRR；挑选"安全帽/塔吊"这类术语型问题和"下雨天能不能施工"这类口语型问题分别看，说明 BM25 补字面匹配、向量补语义匹配。
3. **阈值与拒答**：拒答由 `min-score`（向量路）决定，BM25 默认不参与"是否召回"的判断。把 `min-score` 取 0 / 0.2 / 0.4 / 0.5，统计无关问题（如"今天食堂吃什么"）被拒答的比例与正常问题被误拒的比例，画权衡曲线。再把 `bm25-introduce-new` 改成 `true` 跑一遍，对比"允许 BM25 单独召回"后拒答率如何崩塌——这是本项目的真实踩坑记录。想单独看字面匹配的判别力，还可以在"检索测试"里对比开关前后的召回条数。
4. **topK 对回答质量的影响**：topK 取 1 / 3 / 5 / 10，对比引用条款覆盖率和回答中引用标注的准确性。

## 测试

```bash
mvn test
```

包含 `ClauseTextSplitterTest`（条款切分/章标题合并/超长兜底/PDF 脏排版/目录与页脚过滤）、
`ClauseLabelsTest`（编号压紧/三类条款号/表格数字拒绝/回溯）、`Bm25IndexTest`（关键词排序/删除/分词）、
`HybridRetrieverTest`（7 条检索决策矩阵：无召回不答、BM25 不得单独引入新块、演示模式/向量库故障时放开）、
`DocNamesTest`（磁盘文件名前缀清洗）、`DocumentRegistryTest`（落盘重载/前缀还原/损坏留档/批量销记）
与 `ApiErrorMappingTest`（接口层 5 条：空问题 400、未知任务 404、缺上传字段 400、类型不支持 400、写冲突 409），
共 **39 个用例**。除最后一个用 `@WebMvcTest` 只加载 Web 层（3 秒，不连库、不调模型）外，其余都是纯单元测试。

## 常见问题

- **上传文档 / 重建索引 时报"入库失败"，且 Chroma 窗口里的进程不见了**：
  说明 Chroma 服务端自己崩了（exit 139 段错误），不是应用逻辑问题。常见两种触发：删除不存在的 id、
  索引扩容（见上文 Windows 限制）。确认办法：看 Chroma 窗口有没有退出、`curl localhost:8000/api/v2/heartbeat` 是否还通。
  处理：① 走「想用 Chroma 怎么办」里的路径 A（WSL2）或 C（Docker）换成 Linux 版 Chroma；
  ② 或直接去掉 `chroma` profile，用默认的 SimpleVectorStore（功能等价，零依赖）；
  ③ 若只是数据不一致，可以清空 `data/chroma-server` 后重启应用（会自动建集合）再点"重建索引"。
- **启动就报 `Collection safety-regulations does not exist.`**：这是 Spring AI 1.0.0 用"精确匹配错误文案"判断集合是否存在的 bug（真实 Chroma 返回的文案对不上），本工程已用 `config/ChromaVectorStoreConfig` 接管集合创建；如果你还看到这个报错，说明跑的是旧代码，重新编译即可。
- **回答总是"未找到相关规范"**：先在知识库菜单确认文档已入库；再到"检索测试"看召回分数，必要时调低 `app.retrieval.min-score`。（`min-score`、`min-bm25-score` 等检索参数改完即时生效，只有切分参数才需要"重建索引"。）
- **无关问题也硬答**：说明门槛太松 —— 检查 `min-score` 是否被设成 0，以及两个 BM25 门槛是否被显式填 0（0 表示关闭）。
- **改了切分参数不生效**：切分只发生在入库时，改完参数后在知识库菜单点"重建索引"。
- **上传报"未解析出任何文本内容"**：多半是**扫描版 PDF**（整页是图片、没有文字层）。
  本项目用的是 `PagePdfDocumentReader`，只读文字层；这类文件需要先 OCR 再入库。
  判断办法：拖进阅读器能选中文字就是有文字层。
- **批量上传时某个文件一直"排队中"**：入库是单线程串行跑的，前面的大文件没跑完后面就会排队；
  查 `GET /api/kb/uploads/{jobId}` 能看到当前在跑哪个文件、已经向量化到第几块。
- **换了 Embedding 模型 / 开关了演示模式后检索结果全乱**：启动日志里会有一行
  `向量文件是「X」生成的，当前 Embedding 模型是「Y」`。到知识库菜单点一次"重建索引"即可，
  它会先删掉旧向量再按 `data/docs/` 重新向量化（本项目 586 块约 1 分半，取决于接口速度）。
- **默认实现下"删除文档"删干净了吗**：删。`SimpleVectorStore` 是内存 Map，删不存在的 id 是幂等空操作，
  所以删除时会连同向量一起删掉（实测 589 条 → 586 条）。只有 Chroma 需要先查"哪些 id 真的存在"
  再删——它删不存在的 id 会让服务端段错误。
