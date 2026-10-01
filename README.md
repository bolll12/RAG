# 企业 RAG 服务 · Java

后端已迁移为 **Java 21 + Spring Boot 3.5**。页面、文档上传、知识库管理、BM25／向量混合检索、模型问答、引用核验、请求追踪及命令行评测均由 Java 实现，运行不依赖 Python。

## 快速启动

安装 JDK 21 和 Maven 3.6.3+ 后执行：

```bash
git clone git@github.com:bolll12/RAG.git
cd RAG
cp .env.example .env
./scripts/maven.sh -B -ntp package
./scripts/run.sh
```

- 问答与上传页面：http://127.0.0.1:8000/chat
- 知识 Wiki：http://127.0.0.1:8000/wiki
- API 文档：http://127.0.0.1:8000/docs
- OpenAPI 契约：http://127.0.0.1:8000/openapi.json
- 健康检查：http://127.0.0.1:8000/health（包含 `runtime: java`）

其他机器安装 JDK 21 和 Maven 3.6.3+ 后，直接执行：

```bash
mvn -B -ntp package
java -jar target/rag-service-1.0.0.jar
```

从项目根目录运行，默认读取 `.env` 和 `data/rag.db`。环境变量优先于 `.env`。项目专用 `.tools` 不随源码分发；本机下载的 Oracle JDK 用于开发验证，Docker 使用 Eclipse Temurin。

## 使用页面

左侧「添加文档知识」支持多选 Markdown、TXT、HTML（.html/.htm）、Word（.doc/.docx）和文本型 PDF，每份最多 10 MB。文件逐个入库；成功自动刷新列表，可立即提问。相同知识库中的同名文件更新已有版本，相同内容不重复入库。失败文件显示单独提示，可重新选择重试。

输入知识库名称并按回车后切换查询与上传目标。每次提问独立检索，不把历史对话发给模型；页面最多保留最近 20 条问答，刷新后清空。点击回答中的 `[1]` 等编号可展开原文。

默认使用 **BM25 检索 + 原文摘录**。没有证据时拒答；配置生成模型后启用模型回答。页面不保存服务访问密钥，模型供应商密钥仅在服务端配置。

## 配置模型

复制 `.env.example` 为 `.env`，填写实际服务参数并重启：

```dotenv
RAG_DB=data/rag.db
RAG_API_KEY=
RAG_CHAT_BASE_URL=http://localhost:11434/v1
RAG_CHAT_MODEL=
RAG_CHAT_API_KEY=
RAG_EMBED_BASE_URL=http://localhost:11434/v1
RAG_EMBED_MODEL=
RAG_EMBED_API_KEY=
RAG_TIMEOUT=45
RAG_CHUNK_SIZE=800
RAG_CHUNK_OVERLAP=120
RAG_CONTEXT_CHARS=6000
RAG_MIN_COSINE=0.35
```

填入已部署的模型名称；可使用 Ollama 或企业兼容服务，地址需包含 `/v1`。Java HTTP 客户端调用 `/chat/completions` 与 `/embeddings`；每次调用按 `RAG_TIMEOUT` 超时，供应商错误返回 502，不进行无限重试。

启用嵌入模型后进行 **BM25 + 向量精确检索 + RRF 融合**。启用或更换嵌入模型、地址、切片参数时，使用新的 `RAG_DB` 并重新导入资料；服务检查索引配置和向量维度。供应商替换同名模型仍需操作者主动重建索引。单独更换生成模型无需重建。

本地 BGE-M3 和 Jev 网关已完成实际调用验证；配置模板不包含凭据。RAG 问答和 Wiki 可以分别配置生成模型。

## LLM Wiki

在「知识问答」左侧进入「知识 Wiki」，首次点击「生成 / 更新 Wiki」，把已有文档整理成持久保存的来源摘要和主题知识。主题页汇集多份文档的知识条目，每条附带逐字原文引用、文档版本和字符位置。页面支持关联链接、反向链接、标题搜索、最近 20 版历史查看和 Markdown 导出。

Wiki 问答在已整理的有效知识中检索，再由生成模型回答；选中「将回答保存为知识页」可将结果及来源沉淀为独立页面。生成模型的摘要仍需结合原文判断，程序验证引用确实来自原文，但这不等于证明摘要的全部语义正确。已保存的回答不会再次作为回答证据，避免未经核对的推论循环强化。

阿里云百炼配置示例（实际密钥只写入未跟踪的 `.env`，重启后生效）：

```dotenv
RAG_WIKI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
RAG_WIKI_MODEL=qwen-plus
RAG_WIKI_API_KEY=你的百炼密钥
RAG_WIKI_BATCH_CHARS=2500
RAG_WIKI_TIMEOUT=180
RAG_WIKI_AUTO_UPDATE=true
```

该地址适用于百炼北京地域，也可使用该地域的业务空间专属地址；其他地域须调整地址和密钥，参见[百炼接口说明](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)。Wiki 使用 `/chat/completions` 和 `response_format: {"type":"json_object"}`。如果没有设置 `RAG_WIKI_*` 模型参数，则继承 `RAG_CHAT_*`；显式设置空 `RAG_WIKI_MODEL` 可禁用。bge-m3 和 Jev 分别负责原始文档检索中的向量化与证据判断，Wiki 生成独立调用生成模型。

处理与一致性约定：

- 整理在后台排队执行，页面显示进度和失败原因；同一知识库的重复请求合并，最多排队 32 个知识库。
- 上传、替换和删除原文后自动排队更新（可关闭）。变化会立即将本库页面标记为待更新，旧知识不参与 Wiki 问答；相同内容上传不触发更新。
- 以模型及原文内容缓存提取结果，未变化的段落可复用。更新后的页面及关联在同一事务中发布；期间原文变化则丢弃过期结果，自动更新模式会重新排队。缓存保存在本地数据库中。
- 引用不在原文、格式错误或输出截断时拒绝发布。格式或引用错误最多修正一次；网络错误不会无限重试。失败可手动重试，已完成的提取缓存可复用。服务重启后中断的任务显示失败，可手动继续。
- 旧页面内容保存在版本记录中。删除来源后，相应来源页和失去依据的主题页归档，不再显示；相关问答页保留为待更新历史内容。
- 「检查知识库」检查过期、空页面、孤立页面及缺失主题；目前不自动裁定事实矛盾，不做语义相近主题的自动合并，也不提供手工编辑知识页。

Wiki 与 RAG 共用 SQLite 数据库，新增 `wiki_*` 表，启动时自动创建。原始资料与生成知识分别存储，生成过程不改写原文。所有 `/api/wiki/*` 接口沿用 `RAG_API_KEY` 认证。

| Wiki 接口 | 用途 |
| --- | --- |
| `POST /api/wiki/build` | `{ "collection": "default" }`，返回后台任务，HTTP 202 |
| `GET /api/wiki/status?collection=default` | 模型状态、页面数、待更新数及最近任务进度 |
| `GET /api/wiki/pages?collection=default&q=RAG` | 页面目录，按标题过滤 |
| `GET /api/wiki/pages/{id}?collection=default` | 知识条目、原文引用及双向关联 |
| `GET /api/wiki/pages/{id}/revisions?collection=default` | 最近 20 个版本快照 |
| `GET /api/wiki/pages/{id}/export?collection=default` | 下载 Markdown |
| `POST /api/wiki/ask` | `{ "question": "什么是 RAG？", "collection": "default", "save": true }` |
| `GET /api/wiki/lint?collection=default` | 知识库结构与来源检查 |

## API

| 接口 | 用途 |
| --- | --- |
| `POST /documents` | JSON 入库：text、title、source、collection |
| `POST /documents/upload` | multipart 文件上传，可指定 collection、source |
| `GET /documents?collection=default` | 列出文档及版本 |
| `GET /documents/{id}` | 获取完整原文与元数据 |
| `DELETE /documents/{id}` | 事务内删除文档、切片与向量 |
| `POST /search` | 返回过滤后的候选、排序分数、词覆盖率及各检索通道分数 |
| `POST /ask` | 返回 answer、mode、reason、citations、trace_id |
| `GET /traces/{id}` | 查看耗时、模式、证据 ID 与状态 |

```bash
curl -X POST http://127.0.0.1:8000/documents/upload \
  -F 'file=@examples/demo.md' -F 'collection=default'

curl http://127.0.0.1:8000/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"混合检索为什么使用 RRF？","collection":"default","top_k":3}'
```

设置 `RAG_API_KEY` 后，知识、查询、问答、追踪接口要求 `Authorization: Bearer <密钥>`；页面「查询设置」和 Swagger 的 Authorize 均可填写。健康检查和页面/API 文档公开。集合名称是组织字段，不是权限隔离；共享密钥持有者可访问所有集合。

错误统一返回 `{"detail":"说明"}`：认证失败 401，不存在 404，输入约束失败 422，超限文件 413，模型错误 502。文件格式/编码错误在 Java 版返回 422；旧 Python 版此类上传错误为 400，页面兼容两者。

## 数据迁移与一致性

Java JDBC 直接兼容原 Python SQLite 数据结构，无需再次上传：

- 保留 `documents`、`chunks`、`metadata`、`traces` 表。
- 文档 ID 仍为 `SHA-256(collection + NUL + source)` 的前 32 位十六进制。
- 标题和正文哈希、文档版本与切片 ID 沿用旧规则。
- 切片偏移使用 **Unicode 码点**，与 Python 字符索引一致，包含 emoji 时也不改成 Java UTF-16 下标。
- 索引签名按 JSON 内容比较，兼容旧 Python JSON 中的空格。
- 新版本嵌入完成后，事务内替换旧文档及切片；模型失败不覆盖旧版本。
- 当前只保留最新原文。旧追踪仍保留历史片段 ID，不能恢复已删除版本。

切换前的 SQLite 一致性备份放在 `data/backups/`。Python 源码、依赖清单与测试已归档至本机 `legacy-python/`，不参与 Java 构建与部署。若需回退，先停止 Java，用备份恢复到一个新数据库路径，再明确设置 `RAG_DB` 启动归档实现；不要让两个版本同时写同一数据库。

## 导入与评测

```bash
./scripts/run.sh ingest examples/demo.md
./scripts/run.sh ingest path/to/file.pdf --collection handbook

RAG_DB=data/eval-java.db ./scripts/run.sh ingest examples/demo.md
RAG_DB=data/eval-java.db ./scripts/run.sh eval examples/eval.jsonl --output data/eval-java-report.json
```

JSONL 每行包含 question、expected_sources，可指定 collection。评测返回文档级 Recall@K、MRR@K 和逐题结果；有任一题不满足预期来源或无答案题召回候选时，退出码为 1。示例五条题仅用于冒烟，不代表业务准确率；当前不自动评测生成结论是否被原文支持。

```bash
./scripts/maven.sh -B -ntp test
```

JUnit 覆盖 Unicode 切片、幂等更新与删除、集合过滤、旧索引兼容、嵌入失败保留旧版、引用编号校验、失败追踪、真实 PDF、认证与上传 API，以及兼容模型接口的 HTTP 请求/响应。浏览器验证继续使用原问答页的上传与检索流程。

## 代码结构

```text
src/main/java/com/pingan/rag/
  RagApplication.java          Spring Boot 启动与依赖组装
  RagController.java           HTTP API 与页面入口
  ApiKeyFilter.java            Bearer 认证
  ApiErrors.java               统一错误响应
  RagSettings.java             .env / 环境变量配置
  Contracts.java               输入约束
  DocumentParser.java          UTF-8 / PDFBox / Apache POI 文档解析
  TextProcessing.java          分词、切片与哈希
  RagStore.java                SQLite JDBC 与事务
  RagService.java              入库、混合检索、问答、追踪
  ModelGateway.java            模型接口
  CompatibleModelClient.java   兼容模型 HTTP 实现
  RagCli.java                  导入与检索评测
src/main/resources/static/    问答和上传页面
src/test/java/                JUnit 测试
```

## Docker

```bash
cp .env.example .env
# 填写服务密钥与模型参数。
docker compose up --build -d
```

多阶段构建生成可执行 JAR，使用 Java 21 JRE 启动。Compose 仅发布本机 8000 端口，数据库使用命名卷。容器访问 Docker Desktop 宿主机模型时，将模型地址的 localhost 改为 host.docker.internal。Docker 配置已迁移，但未在当前机器构建验证。

本机默认监听 127.0.0.1；可用进程环境变量 `PORT`、`RAG_HOST`，或 `--server.port=8001` 等 Spring 参数调整。

## 当前边界与材料来源

这是小规模单实例服务：向量保存在 SQLite，检索遍历集合，可选 Jev 证据过滤与重排；尚未实现 ANN、Milvus、异步入库队列、OCR、复杂 PDF 表格恢复、业务有效期过滤或多租户身份系统。

RRF 与余弦分数不是置信度；引用编号合法不等于原文支持结论。需要标注集校准拒答与证据覆盖。服务保存问题哈希、模型名称、耗时、证据 ID 和执行状态；追踪不保存问题/回答正文与密钥，需自行设置保留周期。

用户指定的语雀《RAG 与 Harness 培训材料（理论篇）》仍无法读取，尚未逐项对齐。当前参考并导入的是工作区《企业 RAG 知识体系与问题解决方案》。这里的 Harness 为固定 RAG 链路的输入约束、超时、上下文预算、拒答、引用编号校验、追踪和回归评测设施。

技术参考：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[PDFBox 3](https://pdfbox.apache.org/3.0/getting-started.html)、[springdoc 兼容关系](https://springdoc.org/v2/)、[Ollama 兼容接口](https://github.com/ollama/ollama/blob/main/docs/api/openai-compatibility.mdx)。

## 页面更新与删除文档

文档列表显示当前版本，点击「更新」可选择替换文件。更新使用原 source，即使新文件名不同也保持文档 ID 不变；新内容入库成功后版本递增。点击「删除」后须在弹窗确认，取消不会删除；成功删除后原文及检索片段均移除。历史问答不自动改写，更新或删除后请重新提问。手机端同样提供文档列表和操作入口。

## 按文档选择切片方式

上传区和更新弹窗均可选择：

| 方式 | 行为 |
| --- | --- |
| 段落优先 `paragraph` | 在长度窗口后半段寻找换行或中文句号，兼容原切片规则 |
| 固定长度 `fixed` | 严格按字符长度与重叠窗口切分，不考虑段落和句子边界 |
| 句子优先 `sentence` | 在窗口后半段寻找中英文句末标点（英文句点须后接空白），找不到时按长度上限切分 |

片段长度 100–4000 个 Unicode 字符，重叠长度必须小于片段长度；默认 800/120。字符数不是模型 token 数。这三种方式都是规则切片，不是基于嵌入模型的语义切片。片段加标题必须能够放入服务端上下文预算。

JSON 入库和 multipart 上传都支持 `chunk_strategy`、`chunk_size`、`chunk_overlap`。省略时采用服务端默认值。文档列表返回各自配置；更新弹窗自动回填已有值。相同正文仅调整切片参数也会触发版本递增与原子重建，无需重建整个知识库。旧文档在首次启动时自动补充原有默认配置，正文和旧片段保持原样；CLI 导入目前沿用服务端默认值。


## HTML 上传

上传和更新入口均支持 `.html` / `.htm`，同样适用切片设置与 10 MB 限制。使用 [jsoup](https://jsoup.org/) 解析本地文件，可根据 BOM 或 HTML meta 声明识别编码（未声明时默认 UTF-8）。提取页面标题与正文，保留块级段落、换行和表格单元格分隔，解码 HTML 实体；忽略脚本、样式、模板、嵌入资源和带 hidden/aria-hidden 标记的内容。不执行 JavaScript、不下载外部资源；仅靠脚本生成内容的页面须先导出完整正文。本功能不渲染 CSS，因此不保证完全复现浏览器可见文本，复杂表格也不进行表头关联恢复。

## 原文摘录与章节定位

关键词检索按标题的查询词覆盖率进行有限加权；章节与正文同时参与摘录选择，最多 800 字符并明确标记截断。答案引用展示通过证据校验的片段。此策略为词项匹配和原文摘录，不保证同义词、多跳问题的语义正确性。

页面上传或提问前自动同步知识库输入值，并在当前浏览器标签页会话中记住选定知识库；问答正文和密钥仍不持久化。

Word 上传支持 Word 97–2003（.doc）与 .docx 的正文和表格文本，上传及替换均可选择切片方式。不支持加密文档或图片文字识别；损坏文件会返回解析错误。解析使用 [Apache POI](https://poi.apache.org/text-extraction.html)。

切片修复：段落与句子策略按 Markdown 标题分隔章节（忽略围栏代码块中的注释），重叠不跨章节，重叠长度为上限并优先对齐句子/段落边界；无可用边界的超长内容仍按长度切分。固定长度策略保持原窗口行为。检索与原文摘录移除“什么是”“哪些”等疑问句模板，保留“问题排查”“解决方案”等业务词。

已有索引升级：先备份数据库，再运行 `scripts/run.sh reindex`，从已保存原文重建所有文档，保留文档标识、知识库、来源及切片配置；内容或切片算法变化时版本递增。配置了 Embedding 模型时会重新调用模型生成向量，每份文档事务性更新。

召回与摘录校验：标题奖励按查询词覆盖率计算；英文术语查询的关键词召回要求匹配术语，重复词堆积会降权。“问题排查”“解决方案”等业务词保留，不再全局删除“问题”“解决”。原文摘录同时比较章节和正文，零匹配时拒答。对“什么是 X / X 是什么”的定义问题，采用保守规则识别“X 是/指/用于/通过…”及“X：释义”等解释语句，只摘录定义，不将课程表或关键词提及当作答案；未匹配解释语句时返回 `mode=refused, reason=insufficient_evidence`，未通过该校验的片段不进入答案引用，可通过 `/search` 检查候选。该规则不等同于通用语义判定，隐含定义、同义改写和复杂多问可能需要配置生成模型。

召回去噪配置（修改后重启，无需重建索引）：

- `RAG_MIN_LEXICAL_COVERAGE=0.5`：关键词候选至少覆盖一半的加权查询词；英文术语权重为 2，中文词项为 1。课程领域“培训/学习”作为有限同义词扩展，不是通用语义检索。
- `RAG_MIN_LEXICAL_RATIO=0.5`：保留不低于本次最佳关键词候选分数一半的候选。阈值越高，噪声越少，但也可能漏召回，应通过实际评测校准。
- 单路 BM25 保留加权原始分数；混合模式在各通道过滤后使用 RRF，向量候选独立使用 `RAG_MIN_COSINE`，不要求字面词匹配，保留语义召回能力。
- Top-K 是上限，不补齐弱候选；相同内容（忽略多余空白）跨文档去重，重叠切片仍使用偏移去重，数字不同的片段保留。
- `/search` 返回 `lexical_score`、`query_coverage`、`cosine_score`；`score` 在单路时是加权 BM25，在混合模式时是 RRF，均不是回答正确率。

本地 BGE-M3 配置见 `.env.bge-m3.example`。新机器安装 Ollama 后运行 `ollama serve`，另一个终端执行 `ollama pull bge-m3`；复制此配置模板为 `.env` 后运行 `scripts/run.sh --server.port=8001`，再上传文档生成向量。项目内 `.tools/ollama` 安装包不随源码分发，`scripts/ollama.sh` 仅供已准备此本地工具的环境使用。应用从 `.env` 读取配置；健康接口的 `retrieval=hybrid` 表示已配置 BM25 与向量混合检索，实际向量调用通过提问验证。本机旧 BM25 索引保留于 `data/rag.db`，BGE-M3 索引使用 `data/rag-bge-m3.db`；数据库和用户上传文档不随源码分发。`bge-m3` 是 Embedding 模型，回答仍为原文摘录，生成式回答需另配 `RAG_CHAT_MODEL`。模型提供的其他稀疏/多向量能力未在当前接口启用。参考：[Ollama BGE-M3](https://ollama.com/library/bge-m3)、[Ollama 兼容接口](https://docs.ollama.com/api/openai-compatibility)。

Jev 接入：使用用户提供的 `https://tokendance.space/gateway/typesafe/v1/systemone` 与 `bocha-jev-v1`，按 [TypeSafe HTTP 协议](https://docs.typesafe.ai/api) 发送 `model/state/questions`，解析 `answers.candidate_N.noul`。保留本地 bge-m3，Jev 负责判断候选能否作为答案证据，并按概率降序重排；它不是 Embedding 或文本生成模型。

在服务端 `.env` 填写 `RAG_JEV_API_KEY` 后重启即可启用，无需重建向量。默认最多评估 10 个候选（可设 1–20），配置模板使用阈值 0.9（可设 0–1；未设置环境变量时代码默认值为 0.7）、超时 30 秒（1–180）。候选片段、标题和问题会发送到配置的外部网关；密钥不会发给前端。每次检索有候选时按最多 3 个片段分批请求，无候选时不调用。重排只处理召回候选，不能恢复被前置检索过滤的证据。

`/health` 的 `reranking=jev` 与 `jev_model` 表示配置已启用，不代表网关已验证可用。`/search` 和回答引用中的 `jev_probability` 是“该片段可直接支持回答”的判断概率；原 `score` 保留检索分数。最终结果按 Jev 概率排序；候选数小于 Top-K 时只返回候选范围内通过判断的结果。阈值需用真实数据评测。HTTP 错误、超时、缺失判断或非法概率返回模型错误（502），不静默绕过 Jev；仅对明确的 token_budget_exceeded（422）二分拆批；其他错误不自动重试。单片段超限要求缩短问题或减小切片长度。未配置密钥时保持原检索流程。

本地测试覆盖协议、批量过滤、Top-K 前重排、全拒绝、关闭时不请求及异常响应。真实网关需要有效 API Key 后才能验证；第三方网关如返回不同于 TypeSafe 的封装需另行适配。

Jev 网关展开输入限制实测为 32768 token；共享 state 会随判断数量展开，不能只按原始 JSON 长度估计。页面 `request_count` 展示包括超限尝试在内的实际请求次数，候选总数、概率映射及 Top-K 在各批次合并后计算。
