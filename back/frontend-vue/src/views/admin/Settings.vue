<template>
  <div class="settings-page">
    <el-row :gutter="16">
      <!-- 左侧分类导航 -->
      <el-col :span="5">
        <div class="category-nav">
          <div class="nav-title">系统配置</div>
          <div
            v-for="cat in categories"
            :key="cat.id"
            :class="['nav-item', { active: activeCat === cat.id }]"
            @click="activeCat = cat.id"
          >
            <span class="nav-icon">{{ cat.icon }}</span>
            <span class="nav-label">{{ cat.label }}</span>
          </div>
        </div>
      </el-col>

      <!-- 右侧配置内容 -->
      <el-col :span="19">
        <div class="config-panel">
          <!-- LLM Provider -->
          <div v-if="activeCat === 'llm'" class="config-section">
            <el-alert
              title="环境变量配置"
              description="可通过项目根目录的 .env 文件预设默认 LLM 配置。数据库中的配置优先于环境变量。"
              type="info"
              :closable="false"
              show-icon
              style="margin-bottom: 16px"
            />
            <div class="section-header">
              <h3>大语言模型</h3>
              <el-button type="primary" size="small" @click="showAddLlm = true">+ 添加配置</el-button>
            </div>
            <p class="section-desc">配置 LLM 提供商。启用后系统将使用该模型进行智能问答、知识抽取和深度研究。支持 OpenAI / Anthropic / Google / Ollama / Azure / Custom。</p>
            <el-table :data="llmConfigs" stripe size="small">
              <el-table-column prop="name" label="名称" width="140" />
              <el-table-column prop="provider" label="Provider" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="providerType(row.provider)">{{ row.provider }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="model" label="模型" width="160" />
              <el-table-column prop="base_url" label="Base URL" show-overflow-tooltip />
              <el-table-column prop="purpose" label="用途" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.purpose === 'chat' ? '' : row.purpose === 'ocr' ? 'warning' : 'success'">
                    {{ ({ chat: '对话', embedding: '向量', ocr: 'OCR', both: '两者' } as Record<string, string>)[row.purpose] || row.purpose }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="200" align="center">
                <template #default="{ row }">
                  <div class="action-buttons">
                    <el-tooltip content="测试连接" placement="top" :show-after="300">
                      <el-button
                        size="small"
                        :loading="testingId === row.id"
                        circle
                        @click="testConnection(row)"
                      >
                        <el-icon v-if="testingId !== row.id"><Connection /></el-icon>
                      </el-button>
                    </el-tooltip>
                    <el-tooltip content="编辑配置" placement="top" :show-after="300">
                      <el-button size="small" circle @click="editLlm(row)">
                        <el-icon><Edit /></el-icon>
                      </el-button>
                    </el-tooltip>
                    <el-tooltip :content="row.enabled ? '禁用' : '启用'" placement="top" :show-after="300">
                      <el-button
                        size="small"
                        circle
                        :type="row.enabled ? 'success' : 'info'"
                        @click="toggleLlm(row.id)"
                      >
                        <el-icon><SwitchIcon /></el-icon>
                      </el-button>
                    </el-tooltip>
                    <el-tooltip content="删除配置" placement="top" :show-after="300">
                      <el-button size="small" type="danger" circle @click="deleteLlmConfig(row.id)">
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </el-tooltip>
                  </div>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无 LLM 配置，请点击【新增配置】添加" :image-size="60" />
              </template>
            </el-table>
          </div>

          <!-- OCR 配置 -->
          <div v-if="activeCat === 'ocr'" class="config-section">
            <h3>OCR 图片识别</h3>
            <p class="section-desc">配置视觉语言模型 (VLM)，用于图片中的表格识别、图片描述生成等。这是全局设置，所有项目共享。</p>
            <div style="background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 6px; padding: 12px; margin-bottom: 16px;">
              <p style="margin: 0; color: #0369a1; font-size: 13px;">
                <strong>说明：</strong>OCR 功能需要支持视觉输入的模型（如 Qwen-VL、GPT-4o、Gemini 等）。
                系统将使用此配置处理：图片表格 OCR 识别、图片描述生成、PDF 中的图片提取。
              </p>
            </div>
            <el-form :model="ocrForm" label-width="160px" class="config-form">
              <el-form-item label="启用 OCR">
                <el-switch v-model="ocrForm.enabled" />
              </el-form-item>
              <el-form-item label="使用主 LLM">
                <el-switch v-model="ocrForm.useMainLlm" />
                <div class="form-hint">开启后将复用「大语言模型」中的对话配置，无需单独配置</div>
              </el-form-item>
              <template v-if="!ocrForm.useMainLlm && ocrForm.enabled">
                <el-form-item label="Provider">
                  <el-select v-model="ocrForm.provider" @change="onOcrProviderChange">
                    <el-option label="OpenAI" value="openai" />
                    <el-option label="Anthropic" value="anthropic" />
                    <el-option label="Google" value="google" />
                    <el-option label="SiliconFlow (硅基流动)" value="siliconflow" />
                    <el-option label="Ollama" value="ollama" />
                    <el-option label="Custom" value="custom" />
                  </el-select>
                </el-form-item>
                <el-form-item label="预设快捷选择">
                  <el-select v-model="ocrPresetId" placeholder="选择预设快速填入" clearable @change="applyOcrPreset">
                    <el-option-group v-for="group in ocrPresetGroups" :key="group.label" :label="group.label">
                      <el-option v-for="p in group.items" :key="p.id" :label="p.label" :value="p.id">
                        <span>{{ p.label }}</span>
                        <span style="color: #999; font-size: 12px; margin-left: 8px;">{{ p.hint }}</span>
                      </el-option>
                    </el-option-group>
                  </el-select>
                </el-form-item>
                <el-form-item label="模型">
                  <el-select v-model="ocrForm.model" filterable allow-create placeholder="输入或选择视觉模型">
                    <el-option v-for="m in ocrSuggestedModels" :key="m" :label="m" :value="m" />
                  </el-select>
                  <div v-if="ocrSuggestedModels.length > 0" class="model-chips">
                    <el-tag v-for="m in ocrSuggestedModels.slice(0, 6)" :key="m" size="small" class="model-chip" @click="ocrForm.model = m">{{ m }}</el-tag>
                  </div>
                </el-form-item>
                <el-form-item v-if="ocrForm.provider !== 'google'" label="API Key">
                  <el-input v-model="ocrForm.apiKey" type="password" show-password placeholder="sk-..." />
                </el-form-item>
                <el-form-item v-if="['ollama', 'custom', 'siliconflow'].includes(ocrForm.provider)" label="Base URL">
                  <el-input v-model="ocrForm.baseUrl" placeholder="https://api.example.com/v1" />
                </el-form-item>
                <el-form-item v-if="ocrForm.provider === 'custom'" label="API 模式">
                  <el-select v-model="ocrForm.apiMode">
                    <el-option label="OpenAI Compatible" value="chat_completions" />
                    <el-option label="Anthropic Messages" value="anthropic_messages" />
                  </el-select>
                </el-form-item>
                <el-form-item label="并发数">
                  <el-input-number v-model="ocrForm.concurrency" :min="1" :max="16" />
                  <div class="form-hint">同时处理的图片识别任务数，建议 2-4</div>
                </el-form-item>
              </template>
              <el-form-item>
                <el-button type="primary" @click="saveOcrConfig">保存 OCR 配置</el-button>
                <el-button @click="testOcrConnection" :loading="ocrTesting">测试连接</el-button>
              </el-form-item>
            </el-form>
          </div>

          <!-- Embedding 配置 -->
          <div v-if="activeCat === 'embedding'" class="config-section">
            <h3>向量嵌入</h3>
            <p class="section-desc">配置 Embedding 模型，用于知识词条的语义检索和知识图谱构建。</p>
            <el-form :model="embeddingConfig" label-width="160px" class="config-form">
              <el-form-item label="Embedding Provider">
                <el-select v-model="embeddingConfig.provider">
                  <el-option label="OpenAI" value="openai" />
                  <el-option label="SiliconFlow (硅基流动)" value="siliconflow" />
                  <el-option label="Ollama" value="ollama" />
                  <el-option label="本地 (sentence-transformers)" value="local" />
                  <el-option label="Custom" value="custom" />
                </el-select>
              </el-form-item>
              <el-form-item label="模型">
                <el-select v-model="embeddingConfig.model" filterable allow-create>
                  <el-option v-for="m in embeddingModels" :key="m" :label="m" :value="m" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="embeddingConfig.provider !== 'local'" label="API Key">
                <el-input v-model="embeddingConfig.apiKey" type="password" show-password />
              </el-form-item>
              <el-form-item v-if="['ollama', 'custom', 'siliconflow'].includes(embeddingConfig.provider)" label="Base URL">
                <el-input v-model="embeddingConfig.baseUrl" :placeholder="embeddingConfig.provider === 'ollama' ? 'http://localhost:11434' : embeddingConfig.provider === 'siliconflow' ? 'https://api.siliconflow.cn/v1' : ''" />
              </el-form-item>
              <el-form-item label="向量维度">
                <el-input-number v-model="embeddingConfig.dimensions" :min="128" :max="3072" :step="128" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="saveEmbedding">保存</el-button>
                <el-button @click="testEmbedding">测试连接</el-button>
              </el-form-item>
            </el-form>
          </div>

          <!-- 搜索引擎 -->
          <div v-if="activeCat === 'web-search'" class="config-section">
            <h3>网络搜索</h3>
            <p class="section-desc">深度研究功能使用的网络搜索引擎。参考 llm_wiki 支持多种搜索提供商。</p>
            <el-form :model="searchConfig" label-width="160px" class="config-form">
              <el-form-item label="搜索引擎">
                <el-select v-model="searchConfig.provider">
                  <el-option label="DuckDuckGo（免费，无需 API Key）" value="duckduckgo" />
                  <el-option label="Tavily（推荐，专为 AI 搜索设计）" value="tavily" />
                  <el-option label="Google Custom Search" value="google" />
                  <el-option label="SerpApi（Google/Bing/DDG）" value="serpapi" />
                  <el-option label="Brave Search" value="brave" />
                  <el-option label="SearXNG（自托管）" value="searxng" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="['tavily', 'serpapi', 'brave'].includes(searchConfig.provider)" label="API Key">
                <el-input v-model="searchConfig.apiKey" type="password" show-password :placeholder="searchKeyPlaceholder" />
              </el-form-item>
              <el-form-item v-if="searchConfig.provider === 'google'" label="Google API Key">
                <el-input v-model="searchConfig.googleApiKey" type="password" show-password />
              </el-form-item>
              <el-form-item v-if="searchConfig.provider === 'google'" label="Google CX">
                <el-input v-model="searchConfig.googleCx" placeholder="Custom Search Engine ID" />
              </el-form-item>
              <el-form-item v-if="searchConfig.provider === 'searxng'" label="SearXNG URL">
                <el-input v-model="searchConfig.searxngUrl" placeholder="https://search.example.com" />
              </el-form-item>
              <el-form-item label="最大结果数">
                <el-input-number v-model="searchConfig.maxResults" :min="1" :max="20" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="saveSearchConfig">保存</el-button>
                <el-button @click="testSearch">测试搜索</el-button>
              </el-form-item>
            </el-form>
          </div>

          <!-- 通用设置 -->
          <div v-if="activeCat === 'general'" class="config-section">
            <h3>通用设置</h3>
            <p class="section-desc">配置系统的全局行为参数。</p>
            <el-form :model="generalForm" label-width="200px" class="config-form" v-loading="loadingGeneral">
              <el-divider content-position="left">基本信息</el-divider>
              <el-form-item label="系统名称">
                <el-input v-model="generalForm.system_name" placeholder="智能情报分析平台" />
              </el-form-item>

              <el-divider content-position="left">数据处理</el-divider>
              <el-form-item label="每页显示条数">
                <el-input-number v-model="generalForm.page_size" :min="10" :max="100" :step="10" />
              </el-form-item>
              <el-form-item label="最大上传文件大小 (MB)">
                <el-input-number v-model="generalForm.max_upload_size" :min="10" :max="500" :step="10" />
              </el-form-item>
              <el-form-item label="词条自动审核">
                <el-switch v-model="generalForm.auto_approve_entries" active-text="开启" inactive-text="关闭" />
                <span style="margin-left: 8px; font-size: 12px; color: #94A3B8;">开启后 LLM 抽取的词条将自动通过审核</span>
              </el-form-item>

              <el-divider content-position="left">智能问答</el-divider>
              <el-form-item label="最大检索词条数">
                <el-input-number v-model="generalForm.qa_max_entries" :min="3" :max="30" />
                <div class="form-hint">每次问答最多检索的知识条目数</div>
              </el-form-item>
              <el-form-item label="显示来源标注">
                <el-switch v-model="generalForm.qa_show_sources" active-text="显示" inactive-text="隐藏" />
              </el-form-item>

              <el-divider content-position="left">图片返回设置</el-divider>
              <p style="font-size: 12px; color: #64748B; margin: 0 0 12px;">
                先用相似度阈值筛选（仅返回高于阈值的结果），再用 Top-K 限制显示数量。
              </p>
              <el-form-item label="图片相似度阈值">
                <el-slider v-model="generalForm.qa_image_threshold" :min="0" :max="1" :step="0.05" show-input
                  style="max-width: 400px;" />
                <div class="form-hint">
                  仅返回相似度 ≥ 阈值的图片。值越高结果越精确但可能遗漏，建议 0.5~0.7。当前: {{ generalForm.qa_image_threshold.toFixed(2) }}
                </div>
              </el-form-item>
              <el-form-item label="图片最大返回数 (Top-K)">
                <el-input-number v-model="generalForm.qa_image_topK" :min="0" :max="20" />
                <div class="form-hint">通过阈值筛选后，最多显示的图片数量。设为 0 表示不返回图片</div>
              </el-form-item>
              <el-form-item label="表格相似度阈值">
                <el-slider v-model="generalForm.qa_table_threshold" :min="0" :max="1" :step="0.05" show-input
                  style="max-width: 400px;" />
                <div class="form-hint">仅返回相似度 ≥ 阈值的表格。建议 0.5~0.7。当前: {{ generalForm.qa_table_threshold.toFixed(2) }}</div>
              </el-form-item>
              <el-form-item label="表格最大返回数 (Top-K)">
                <el-input-number v-model="generalForm.qa_table_topK" :min="0" :max="10" />
                <div class="form-hint">通过阈值筛选后，最多显示的表格数量。设为 0 表示不返回表格</div>
              </el-form-item>

              <el-divider content-position="left">深度研究</el-divider>
              <el-form-item label="搜索查询数量">
                <el-input-number v-model="generalForm.research_query_count" :min="2" :max="10" />
                <span style="margin-left: 8px; font-size: 12px; color: #94A3B8;">每个研究任务生成的搜索查询数</span>
              </el-form-item>
              <el-form-item label="每个查询最大结果数">
                <el-input-number v-model="generalForm.research_max_results_per_query" :min="3" :max="20" />
              </el-form-item>

              <el-divider content-position="left">存储路径</el-divider>
              <el-form-item label="上传文件存储目录">
                <el-input v-model="generalForm.upload_dir" placeholder="默认: ./uploads（相对于后端启动目录）">
                  <template #append>
                    <el-tooltip content="修改后需重启后端服务才能生效" placement="top">
                      <el-icon><InfoFilled /></el-icon>
                    </el-tooltip>
                  </template>
                </el-input>
                <div class="form-hint">上传文件、来源资料的存储根目录。可使用绝对路径（如 /data/uploads）或相对路径（如 ./my_uploads）。修改后需重启后端服务。</div>
              </el-form-item>

              <el-divider content-position="left">界面设置</el-divider>
              <el-form-item label="默认语言">
                <el-select v-model="generalForm.language">
                  <el-option label="中文" value="zh" />
                  <el-option label="English" value="en" />
                </el-select>
              </el-form-item>
              <el-form-item label="主题">
                <el-select v-model="generalForm.theme">
                  <el-option label="浅色" value="light" />
                  <el-option label="深色" value="dark" />
                  <el-option label="跟随系统" value="system" />
                </el-select>
              </el-form-item>

              <el-form-item>
                <el-button type="primary" @click="saveGeneral">保存配置</el-button>
                <el-button @click="loadGeneral">重置</el-button>
              </el-form-item>
            </el-form>
          </div>

          <!-- 图谱构建 -->
          <div v-if="activeCat === 'kg'" class="config-section">
            <h3>知识图谱构建</h3>
            <p class="section-desc">配置知识图谱的构建策略。参考 llm_wiki 的 wiki-graph.ts：从知识词条中提取实体和关系，使用 Louvain 社区检测算法进行聚类。</p>
            <el-form :model="kgConfig" label-width="180px" class="config-form">
              <el-form-item label="实体抽取策略">
                <el-select v-model="kgConfig.extractionStrategy">
                  <el-option label="LLM 抽取（推荐）" value="llm" />
                  <el-option label="规则抽取" value="rule" />
                  <el-option label="混合模式" value="hybrid" />
                </el-select>
                <div class="strategy-desc">
                  <div v-if="kgConfig.extractionStrategy === 'llm'" class="desc-item">
                    <strong>LLM 抽取</strong>：使用大语言模型自动识别文档中的实体和关系。优势：理解语义，可识别隐含关系；劣势：需要API调用，成本较高。
                  </div>
                  <div v-if="kgConfig.extractionStrategy === 'rule'" class="desc-item">
                    <strong>规则抽取</strong>：基于预定义的NLP规则（如NER命名实体识别、依存句法分析）提取实体和关系。优势：速度快、成本低；劣势：只能识别显式表达的关系。
                  </div>
                  <div v-if="kgConfig.extractionStrategy === 'hybrid'" class="desc-item">
                    <strong>混合模式</strong>：先用规则快速提取显式关系，再用LLM补充语义理解和隐含关系。优势：兼顾效率和质量；劣势：处理时间较长。
                  </div>
                </div>
              </el-form-item>
              <el-form-item label="关系类型">
                <el-checkbox-group v-model="kgConfig.edgeTypes">
                  <el-checkbox label="belongs_to">所属 (belongs_to)</el-checkbox>
                  <el-checkbox label="cites">引用 (cites)</el-checkbox>
                  <el-checkbox label="supports">支持 (supports)</el-checkbox>
                  <el-checkbox label="contradicts">矛盾 (contradicts)</el-checkbox>
                  <el-checkbox label="related_to">相关 (related_to)</el-checkbox>
                  <el-checkbox label="derived_from">衍生 (derived_from)</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
              <el-form-item label="社区检测算法">
                <el-select v-model="kgConfig.communityAlgorithm">
                  <el-option label="Louvain（推荐）" value="louvain" />
                  <el-option label="Label Propagation" value="label_propagation" />
                  <el-option label="无社区检测" value="none" />
                </el-select>
              </el-form-item>
              <el-form-item label="最小社区大小">
                <el-input-number v-model="kgConfig.minCommunitySize" :min="2" :max="10" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="saveKgConfig">保存</el-button>
                <el-button @click="rebuildGraph" :loading="rebuilding">重建图谱</el-button>
                <el-button type="success" @click="rebuildVectorIdx" :loading="rebuildingVector">重建向量索引</el-button>
              </el-form-item>
              <el-form-item v-if="vectorStats.size > 0">
                <div class="vector-stats">
                  向量索引：{{ vectorStats.size }} 个向量，维度 {{ vectorStats.dimension }}
                  <el-tag v-if="vectorStats.hasEmbeddingConfig" type="success" size="small">Embedding已配置</el-tag>
                  <el-tag v-else type="warning" size="small">未配置Embedding</el-tag>
                </div>
              </el-form-item>
            </el-form>
          </div>

          <!-- API 接入 -->
          <div v-if="activeCat === 'api-access'" class="config-section">
            <h3>API 接入与集成</h3>
            <p class="section-desc">本平台提供开放 API 接口，供第三方系统（如自建分析平台、OA、机器人等）进行智能问答、数据导入及知识图谱集成。</p>
            
            <div style="background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 16px; margin-bottom: 20px;">
              <p style="margin: 0; color: #16a34a; font-size: 14px; font-weight: bold;">
                当前项目ID：{{ currentProjectId || '未选择' }}
              </p>
              <p style="margin: 6px 0 0 0; color: #15803d; font-size: 13px; line-height: 1.5;">
                在外部调用所有 API 接口时，必须在 HTTP Request Headers 中携带项目隔离请求头：<code>X-Project-Id: {{ currentProjectId || '11' }}</code>，否则平台无法隔离提取各项目数据。
              </p>
            </div>

            <el-tabs type="border-card" style="border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.05); margin-bottom: 20px;">
              <el-tab-pane label="智能问答 Ask API">
                <div class="api-doc-wrap">
                  <h4 style="margin-top: 0; color: #1e3a8a;">1. 智能问答接口 (RAG Chat)</h4>
                  <p class="api-meta">
                    <span class="api-method post">POST</span>
                    <code class="api-path">/api/qa-chat/ask</code>
                  </p>
                  <p class="api-desc">基于平台上传的资料和知识图谱，结合大语言模型进行智能问答。返回结果中包含多模态图表、表格数据及详细来源引用。</p>
                  
                  <h5 style="margin-bottom: 8px; color: #334155;">请求 Body (JSON)：</h5>
                  <pre class="code-block"><code>{
  "question": "社区医疗主要承担什么功能？",
  "history": []
}</code></pre>
                  
                  <h5 style="margin-bottom: 8px; color: #334155;">响应 Response (JSON)：</h5>
                  <pre class="code-block"><code>{
  "answer": "根据相关报告，社区医疗主要承担以下功能...",
  "confidence": 0.70,
  "sources": [
    { "id": 54, "title": "社区医疗", "content": "..." }
  ],
  "tables": [],
  "images": []
}</code></pre>
                </div>
              </el-tab-pane>

              <el-tab-pane label="文档/链接上传 API">
                <div class="api-doc-wrap">
                  <h4 style="margin-top: 0; color: #1e3a8a;">2. 文档与网页上传接口</h4>
                  <p class="api-meta">
                    <span class="api-method post">POST</span>
                    <code class="api-path">/api/upload/</code>
                  </p>
                  <p class="api-desc">上传本地文件（PDF, Word, TXT, Excel, PNG, JPG）到指定的资料库，系统会自动执行 OCR 及大模型知识提取。</p>
                  
                  <h5 style="margin-bottom: 8px; color: #334155;">请求 Headers：</h5>
                  <pre class="code-block"><code>Content-Type: multipart/form-data
X-Project-Id: {{ currentProjectId || '11' }}</code></pre>

                  <h5 style="margin-bottom: 8px; color: #334155;">请求 Form Data 参数：</h5>
                  <table class="api-table" style="width: 100%; border-collapse: collapse; margin-bottom: 20px;">
                    <thead>
                      <tr style="background: #f8fafc; border-bottom: 2px solid #e2e8f0;">
                        <th style="padding: 10px; text-align: left; font-size: 13px; color: #64748b; font-weight: 600;">参数</th>
                        <th style="padding: 10px; text-align: left; font-size: 13px; color: #64748b; font-weight: 600;">类型</th>
                        <th style="padding: 10px; text-align: left; font-size: 13px; color: #64748b; font-weight: 600;">必填</th>
                        <th style="padding: 10px; text-align: left; font-size: 13px; color: #64748b; font-weight: 600;">描述</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px; font-size: 12px; font-weight: bold; font-family: monospace;">file</td>
                        <td style="padding: 10px; font-size: 12px; color: #475569;">File</td>
                        <td style="padding: 10px; font-size: 12px; color: #ef4444; font-weight: bold;">是</td>
                        <td style="padding: 10px; font-size: 12px; color: #475569;">要上传的原始物理文件（如报告 PDF）。</td>
                      </tr>
                      <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px; font-size: 12px; font-weight: bold; font-family: monospace;">docType</td>
                        <td style="padding: 10px; font-size: 12px; color: #475569;">String</td>
                        <td style="padding: 10px; font-size: 12px; color: #ef4444; font-weight: bold;">是</td>
                        <td style="padding: 10px; font-size: 12px; color: #475569;">资料库分类：<code>report</code> (研究报告), <code>dynamic</code> (动态信息), <code>translation</code> (图书), <code>chart</code> (图表)。</td>
                      </tr>
                      <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px; font-size: 12px; font-weight: bold; font-family: monospace;">sourceOrigin</td>
                        <td style="padding: 10px; font-size: 12px; color: #475569;">String</td>
                        <td style="padding: 10px; font-size: 12px; color: #64748b;">否</td>
                        <td style="padding: 10px; font-size: 12px; color: #475569;">文件来源出处/出版渠道说明。</td>
                      </tr>
                    </tbody>
                  </table>
                  
                  <h4 style="margin-top: 20px; color: #1e3a8a;">3. 网页链接爬取上传接口</h4>
                  <p class="api-meta">
                    <span class="api-method post">POST</span>
                    <code class="api-path">/api/upload/from-url</code>
                  </p>
                  <p class="api-desc">直接提供网络 URL 地址，后端将自动提取网页正文、清洗并作为动态信息入库。</p>
                  <h5 style="margin-bottom: 8px; color: #334155;">请求 Query 参数 (URL Params)：</h5>
                  <pre class="code-block"><code>url=https://example.com/some-article-link
docType=dynamic
sourceOrigin=网页导入</code></pre>
                </div>
              </el-tab-pane>

              <el-tab-pane label="知识图谱 API">
                <div class="api-doc-wrap">
                  <h4 style="margin-top: 0; color: #1e3a8a;">4. 获取项目知识图谱数据</h4>
                  <p class="api-meta">
                    <span class="api-method get">GET</span>
                    <code class="api-path">/api/kg/graph</code>
                  </p>
                  <p class="api-desc">返回当前项目构建出的所有核心概念实体（Nodes）以及它们之间的关联边（Edges）。支持用于前端拓扑图渲染。</p>
                  
                  <h5 style="margin-bottom: 8px; color: #334155;">响应 Response (JSON)：</h5>
                  <pre class="code-block"><code>{
  "nodes": [
    { "id": 1, "label": "社区医疗", "nodeType": "concept", "communityId": 0 }
  ],
  "edges": [
    { "id": 5, "sourceId": 1, "targetId": 2, "edgeType": "keyword_overlap", "weight": 2.0 }
  ]
}</code></pre>
                </div>
              </el-tab-pane>

              <el-tab-pane label="Python 调用示例">
                <div class="api-doc-wrap">
                  <h4 style="margin-top: 0; color: #1e3a8a;">使用 Python 请求问答示例</h4>
                  <p class="api-desc">使用 <code>requests</code> 库与本平台 API 交互的脚本模版：</p>
                  <pre class="code-block"><code>import requests
import json

url = "http://localhost:8080/api/qa-chat/ask"
headers = {
    "X-Project-Id": "{{ currentProjectId || '11' }}",
    "Content-Type": "application/json"
}

data = {
    "question": "什么是分级诊疗？其核心原则是什么？",
    "history": []
}

try:
    response = requests.post(url, headers=headers, json=data)
    response.raise_for_status()
    result = response.json()
    
    print("AI 答复：")
    print(result.get("answer"))
    
    print("\n信息来源归因：")
    for src in result.get("sources", []):
        print(f"- [{src.get('id')}] {src.get('title')}")
except Exception as e:
    print("请求出错：", e)
</code></pre>
                </div>
              </el-tab-pane>

              <el-tab-pane label="cURL 命令行测试">
                <div class="api-doc-wrap">
                  <h4 style="margin-top: 0; color: #1e3a8a;">cURL 命令行问答</h4>
                  <p class="api-desc">在命令行终端快速执行 HTTP 请求以测试连接：</p>
                  <pre class="code-block"><code>curl -X POST http://localhost:8080/api/qa-chat/ask \
  -H "X-Project-Id: {{ currentProjectId || '11' }}" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "社区医疗主要承担什么功能？",
    "history": []
  }'</code></pre>
                </div>
              </el-tab-pane>
            </el-tabs>
          </div>

          <!-- 桌面设置 -->
          <div v-if="activeCat === 'desktop'" class="config-section">
            <h3>桌面应用设置</h3>
            <p class="section-desc">配置本地桌面端的特定选项，包括系统托盘行为以及查看本地运行日志。</p>
            
            <el-form :model="desktopForm" label-width="140px" style="max-width: 600px;">
              <el-form-item label="数据存储路径">
                <div style="display: flex; gap: 10px; width: 100%;">
                  <el-input v-model="desktopForm.customDataDir" placeholder="默认系统路径，点击右侧选择自定义目录..." readonly />
                  <el-button type="primary" size="small" @click="handleSelectSettingsDir">选择目录</el-button>
                </div>
                <div class="form-hint" style="color: #94A3B8; font-size: 12px; margin-top: 4px;">保存 SQLite 数据库和上传文档的本地目录。修改后需重启应用以重新初始化后端。</div>
              </el-form-item>
              
              <el-form-item label="关闭时行为">
                <el-radio-group v-model="desktopForm.closeAction">
                  <el-radio label="minimize">最小化到系统托盘</el-radio>
                  <el-radio label="quit">直接退出应用</el-radio>
                </el-radio-group>
                <div class="form-hint" style="color: #94A3B8; font-size: 12px; margin-top: 4px;">选择点击窗口关闭按钮（X）时的操作。</div>
              </el-form-item>
              
              <el-form-item label="更新检查频率">
                <el-select v-model="desktopForm.checkUpdateFrequency">
                  <el-option label="每次启动" value="startup" />
                  <el-option label="每天一次" value="daily" />
                  <el-option label="每周一次" value="weekly" />
                  <el-option label="手动检查" value="manual" />
                </el-select>
              </el-form-item>
              
              <el-form-item>
                <el-button type="primary" @click="saveDesktopConfig">保存设置</el-button>
                <el-button type="success" @click="handleOpenDataDir">打开数据目录</el-button>
                <el-button type="info" @click="handleCheckUpdate">检查更新</el-button>
              </el-form-item>
            </el-form>

            <el-divider style="margin: 24px 0;" />

            <!-- 本地运行日志 -->
            <div class="logs-section">
              <div class="logs-header" style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <h4 style="margin: 0; font-size: 15px; color: #ffffff;">本地引擎运行日志</h4>
                <div class="logs-actions">
                  <el-button size="small" type="primary" :loading="loadingLogs" @click="fetchLogs">刷新日志</el-button>
                </div>
              </div>
              
              <div class="logs-console" style="background: #1e1e1e; border-radius: 8px; padding: 12px; font-family: monospace; font-size: 12px; color: #d4d4d4; max-height: 350px; overflow-y: auto; line-height: 1.6; border: 1px solid #333;">
                <div v-for="(log, idx) in logs" :key="idx" class="log-line" style="white-space: pre-wrap; margin-bottom: 4px; text-align: left;">
                  {{ log }}
                </div>
                <div v-if="logs.length === 0" style="color: #6a9955; text-align: center; padding: 20px 0;">
                  无日志记录
                </div>
              </div>
            </div>
          </div>

          <!-- 系统日志 -->
          <div v-if="activeCat === 'system-logs'" class="config-section">
            <h3>系统日志</h3>
            <p class="section-desc">查看和管理系统运行日志。日志文件保存在 <code>~/.pku-platform/logs/app.log</code>，自动轮转保留最近 7 天。</p>

            <div style="display: flex; gap: 12px; margin-bottom: 20px;">
              <el-button type="primary" @click="openLogViewer">
                <el-icon style="margin-right: 4px;"><Document /></el-icon>
                查看日志
              </el-button>
              <el-button type="success" @click="openLogDir">
                <el-icon style="margin-right: 4px;"><FolderOpened /></el-icon>
                打开日志目录
              </el-button>
            </div>

            <div class="logs-section">
              <div class="logs-header" style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <h4 style="margin: 0; font-size: 15px;">最近日志（{{ systemLogs.length }} 行）</h4>
                <div class="logs-actions">
                  <el-button size="small" type="primary" :loading="loadingSystemLogs" @click="fetchSystemLogs">刷新</el-button>
                </div>
              </div>

              <div class="logs-console" style="background: #1e1e1e; border-radius: 8px; padding: 12px; font-family: monospace; font-size: 12px; color: #d4d4d4; max-height: 450px; overflow-y: auto; line-height: 1.6; border: 1px solid #333;">
                <div v-for="(log, idx) in systemLogs" :key="idx" class="log-line" style="white-space: pre-wrap; margin-bottom: 4px; text-align: left;">
                  {{ log }}
                </div>
                <div v-if="systemLogs.length === 0" style="color: #6a9955; text-align: center; padding: 20px 0;">
                  暂无日志记录，系统运行后会自动生成日志
                </div>
              </div>
            </div>
          </div>

        </div>
      </el-col>
    </el-row>

    <!-- 系统日志对话框 -->
    <el-dialog v-model="showLogDialog" title="系统日志" width="90%" top="5vh" destroy-on-close>
      <div style="display: flex; justify-content: flex-end; margin-bottom: 8px;">
        <el-button size="small" type="primary" :loading="loadingSystemLogs" @click="fetchSystemLogs">刷新</el-button>
      </div>
      <div class="logs-console" style="background: #1e1e1e; border-radius: 8px; padding: 12px; font-family: monospace; font-size: 12px; color: #d4d4d4; height: 60vh; overflow-y: auto; line-height: 1.6; border: 1px solid #333;">
        <div v-for="(log, idx) in systemLogs" :key="idx" style="white-space: pre-wrap; margin-bottom: 4px;">
          {{ log }}
        </div>
        <div v-if="systemLogs.length === 0" style="color: #6a9955; text-align: center; padding: 40px 0;">
          暂无日志记录
        </div>
      </div>
    </el-dialog>

    <!-- LLM 配置对话框 -->
    <el-dialog v-model="showAddLlm" :title="editingLlm ? '编辑 LLM 配置' : '添加 LLM 配置'" width="700px" top="3vh">
      <el-form :model="llmForm" label-width="140px">
        <el-form-item label="快速预设">
          <el-select v-model="selectedPresetId" placeholder="选择预设快速填充" @change="applyPreset" filterable clearable style="width: 100%;">
            <el-option-group v-for="group in presetGroups" :key="group.label" :label="group.label">
              <el-option v-for="p in group.items" :key="p.id" :label="p.label" :value="p.id">
                <span>{{ p.label }}</span>
                <span style="color: #94A3B8; font-size: 11px; margin-left: 8px;">{{ p.hint }}</span>
              </el-option>
            </el-option-group>
          </el-select>
        </el-form-item>

        <el-divider style="margin: 12px 0;" />

        <el-form-item label="配置名称">
          <el-input v-model="llmForm.name" placeholder="如：OpenAI GPT-4o" />
        </el-form-item>
        <el-form-item label="Provider">
          <el-select v-model="llmForm.provider" @change="onProviderChange">
            <el-option label="OpenAI" value="openai" />
            <el-option label="Anthropic (Claude)" value="anthropic" />
            <el-option label="Google (Gemini)" value="google" />
            <el-option label="Azure OpenAI" value="azure" />
            <el-option label="SiliconFlow (硅基流动)" value="siliconflow" />
            <el-option label="Ollama (本地)" value="ollama" />
            <el-option label="Custom (自定义)" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="llmForm.model" filterable allow-create placeholder="输入或选择模型">
            <el-option v-for="m in currentSuggestedModels" :key="m" :label="m" :value="m" />
          </el-select>
          <div v-if="currentSuggestedModels.length > 0" class="model-chips">
            <el-tag v-for="m in currentSuggestedModels.slice(0, 8)" :key="m" size="small" class="model-chip" @click="llmForm.model = m">{{ m }}</el-tag>
          </div>
        </el-form-item>
        <el-form-item v-if="llmForm.provider !== 'google'" label="API Key">
          <el-input v-model="llmForm.apiKey" type="password" show-password placeholder="sk-..." />
        </el-form-item>
        <el-form-item v-if="['ollama', 'custom', 'azure', 'siliconflow'].includes(llmForm.provider)" label="Base URL">
          <el-input v-model="llmForm.baseUrl" :placeholder="defaultBaseUrl" />
        </el-form-item>
        <el-form-item v-if="llmForm.provider === 'custom'" label="API 模式">
          <el-select v-model="llmForm.apiMode">
            <el-option label="OpenAI Compatible (chat_completions)" value="chat_completions" />
            <el-option label="Anthropic Messages" value="anthropic_messages" />
          </el-select>
        </el-form-item>
        <el-form-item label="用途">
          <el-select v-model="llmForm.purpose">
            <el-option label="对话（智能问答/深度研究）" value="chat" />
            <el-option label="向量（语义检索/图谱构建）" value="embedding" />
            <el-option label="OCR（图片/表格识别）" value="ocr" />
            <el-option label="两者" value="both" />
          </el-select>
        </el-form-item>
        <el-form-item label="上下文窗口">
          <el-select v-model="llmForm.maxContextSize">
            <el-option :label="'4,096 tokens'" :value="4096" />
            <el-option :label="'8,192 tokens'" :value="8192" />
            <el-option :label="'16,384 tokens'" :value="16384" />
            <el-option :label="'32,768 tokens'" :value="32768" />
            <el-option :label="'64,000 tokens'" :value="64000" />
            <el-option :label="'128,000 tokens'" :value="128000" />
            <el-option :label="'200,000 tokens'" :value="200000" />
            <el-option :label="'256,000 tokens'" :value="256000" />
            <el-option :label="'1,000,000 tokens'" :value="1000000" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddLlm = false">取消</el-button>
        <el-button type="primary" @click="saveLlm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Switch as SwitchIcon, Delete, Connection, Document, FolderOpened } from '@element-plus/icons-vue'
import {
  getLlmConfigs, createLlmConfig, updateLlmConfig, toggleLlmConfig,
  deleteLlmConfig as deleteLlmConfigApi, testLlmConnection,
  getSettings, updateSettings, buildKGGraph,
  getSearchConfig as getSearchConfigApi, saveSearchConfig as saveSearchConfigApi, testSearchConfig as testSearchConfigApi,
  getVectorStats, rebuildVectorIndex, getCurrentProjectId
} from '../../api'

interface Category {
  id: string
  label: string
  icon: string
}

const categories: Category[] = [
  { id: 'llm', label: '大语言模型', icon: '🤖' },
  { id: 'ocr', label: 'OCR 识别', icon: '👁️' },
  { id: 'embedding', label: '向量嵌入', icon: '📐' },
  { id: 'web-search', label: '网络搜索', icon: '🌐' },
  { id: 'kg', label: '图谱构建', icon: '🕸️' },
  { id: 'general', label: '通用设置', icon: '⚙️' },
  { id: 'desktop', label: '桌面设置', icon: '💻' },
  { id: 'api-access', label: 'API 接入', icon: '🔌' },
  { id: 'system-logs', label: '系统日志', icon: '📋' },
]

const activeCat = ref('llm')
const currentProjectId = ref<number | null>(getCurrentProjectId())

// LLM Config
const llmConfigs = ref<any[]>([])
const showAddLlm = ref(false)
const editingLlm = ref<any>(null)
const testingId = ref<number | null>(null)

const llmForm = reactive({
  name: '', provider: 'openai', apiKey: '', model: '',
  baseUrl: '', purpose: 'chat', maxContextSize: 4096,
  apiMode: 'chat_completions', description: ''
})

// LLM预设数据 - 完全参考 llm_wiki/src/components/settings/llm-presets.ts
interface LlmPreset {
  id: string; label: string; hint?: string; provider: string;
  baseUrl?: string; defaultModel?: string; apiMode?: string;
  suggestedModels?: string[]; suggestedContextSize?: number;
}

const LLM_PRESETS: LlmPreset[] = [
  { id: 'anthropic', label: 'Anthropic (Claude)', hint: '官方 Claude API', provider: 'anthropic', defaultModel: 'claude-sonnet-4-5-20250929', suggestedModels: ['claude-opus-4-7','claude-opus-4-6','claude-sonnet-4-6','claude-sonnet-4-5-20250929','claude-haiku-4-5-20251001','claude-opus-4-5-20251101','claude-sonnet-4-20250514','claude-3-5-sonnet-20241022','claude-3-5-haiku-20241022'], suggestedContextSize: 200000 },
  { id: 'claude-code-cli', label: 'Claude Code CLI (本地)', hint: '使用本地 claude 二进制，无需 API Key', provider: 'claude-code', defaultModel: 'claude-sonnet-4-6', suggestedModels: ['claude-opus-4-7','claude-opus-4-6','claude-sonnet-4-6','claude-sonnet-4-5-20250929','claude-haiku-4-5-20251001'], suggestedContextSize: 200000 },
  { id: 'codex-cli', label: 'Codex CLI (本地)', hint: '使用本地 codex 二进制，无需 API Key', provider: 'codex-cli', defaultModel: 'gpt-5.4-mini', suggestedModels: ['gpt-5.4-mini','gpt-5.4','gpt-5.3-codex','gpt-5.3-codex-spark','gpt-5.2'], suggestedContextSize: 200000 },
  { id: 'openai', label: 'OpenAI (GPT)', hint: '官方 OpenAI API', provider: 'openai', defaultModel: 'gpt-4o', suggestedModels: ['gpt-4o','gpt-4o-mini','gpt-4.1','gpt-4.1-mini','gpt-4.1-nano','o3','o3-mini','o1','o1-mini','gpt-4-turbo'], suggestedContextSize: 128000 },
  { id: 'google', label: 'Google (Gemini)', hint: 'Generative Language API', provider: 'google', defaultModel: 'gemini-2.5-flash', suggestedModels: ['gemini-2.5-pro','gemini-2.5-flash','gemini-2.5-flash-lite','gemini-2.0-flash','gemini-2.0-flash-lite','gemini-1.5-pro','gemini-1.5-flash'], suggestedContextSize: 1000000 },
  { id: 'azure', label: 'Azure OpenAI', hint: 'Azure OpenAI 资源端点', provider: 'azure', baseUrl: 'https://your-resource.openai.azure.com', defaultModel: 'your-deployment-name', suggestedContextSize: 128000 },
  { id: 'deepseek', label: 'DeepSeek', hint: 'api.deepseek.com', provider: 'custom', baseUrl: 'https://api.deepseek.com/v1', defaultModel: 'deepseek-v4-flash', apiMode: 'chat_completions', suggestedModels: ['deepseek-v4-flash','deepseek-v4-pro','deepseek-chat','deepseek-reasoner'], suggestedContextSize: 64000 },
  { id: 'siliconflow', label: 'SiliconFlow (硅基流动)', hint: 'api.siliconflow.cn', provider: 'siliconflow', baseUrl: 'https://api.siliconflow.cn/v1', defaultModel: 'Qwen/Qwen2.5-72B-Instruct', apiMode: 'chat_completions', suggestedModels: ['Qwen/Qwen2.5-72B-Instruct','Qwen/Qwen2.5-32B-Instruct','Qwen/Qwen2.5-14B-Instruct','Qwen/Qwen2.5-7B-Instruct','Qwen/Qwen2.5-Coder-32B-Instruct','deepseek-ai/DeepSeek-V3','deepseek-ai/DeepSeek-R1','Pro/deepseek-ai/DeepSeek-V3','Pro/deepseek-ai/DeepSeek-R1','THUDM/glm-4-9b-chat','01-ai/Yi-1.5-34B-Chat-16K','internlm/internlm2_5-20b-chat','meta-llama/Meta-Llama-3.1-70B-Instruct'], suggestedContextSize: 32768 },
  { id: 'siliconflow-vision', label: 'SiliconFlow VLM (视觉模型)', hint: 'api.siliconflow.cn 多模态', provider: 'siliconflow', baseUrl: 'https://api.siliconflow.cn/v1', defaultModel: 'Pro/Qwen/Qwen2-VL-7B-Instruct', apiMode: 'chat_completions', suggestedModels: ['Pro/Qwen/Qwen2-VL-7B-Instruct','Qwen/Qwen2-VL-72B-Instruct','OpenGVLab/InternVL2-26B','Pro/OpenGVLab/InternVL2-8B','deepseek-ai/deepseek-vl2'], suggestedContextSize: 32768 },
  { id: 'atlascloud', label: 'Atlas Cloud', hint: 'api.atlascloud.ai', provider: 'custom', baseUrl: 'https://api.atlascloud.ai/v1', defaultModel: 'deepseek-ai/deepseek-v4-pro', apiMode: 'chat_completions', suggestedModels: ['deepseek-ai/deepseek-v4-pro','deepseek-ai/deepseek-v4-flash','deepseek-ai/deepseek-v3.2','Qwen/Qwen3-Next-80B-A3B-Instruct','moonshotai/kimi-k2.6','zai-org/glm-5','minimaxai/minimax-m2.7','anthropic/claude-sonnet-4.6','openai/gpt-5.5','google/gemini-3.5-flash'], suggestedContextSize: 128000 },
  { id: 'groq', label: 'Groq', hint: 'api.groq.com', provider: 'custom', baseUrl: 'https://api.groq.com/openai/v1', defaultModel: 'llama-3.3-70b-versatile', apiMode: 'chat_completions', suggestedModels: ['llama-3.3-70b-versatile','llama-3.1-8b-instant','llama-3.1-70b-versatile','mixtral-8x7b-32768','gemma2-9b-it','moonshotai/kimi-k2-instruct','openai/gpt-oss-120b','openai/gpt-oss-20b','qwen/qwen3-32b'], suggestedContextSize: 128000 },
  { id: 'xai', label: 'xAI (Grok)', hint: 'api.x.ai', provider: 'custom', baseUrl: 'https://api.x.ai/v1', defaultModel: 'grok-3', apiMode: 'chat_completions', suggestedModels: ['grok-4-latest','grok-4','grok-3','grok-3-mini','grok-3-fast','grok-3-mini-fast','grok-code-fast-1','grok-2-vision-1212'], suggestedContextSize: 131072 },
  { id: 'nvidia-nim', label: 'NVIDIA NIM', hint: 'integrate.api.nvidia.com', provider: 'custom', baseUrl: 'https://integrate.api.nvidia.com/v1', apiMode: 'chat_completions', defaultModel: 'meta/llama-3.3-70b-instruct', suggestedModels: ['nvidia/llama-3.3-nemotron-super-49b-v1.5','nvidia/nemotron-3-super-120b-a12b','nvidia/nemotron-3-nano-30b-a3b','meta/llama-3.3-70b-instruct','meta/llama-3.1-405b-instruct','meta/llama-3.1-70b-instruct','deepseek-ai/deepseek-v3.2','moonshotai/kimi-k2.6','qwen/qwen3.5-397b-a17b','minimaxai/minimax-m2.7'], suggestedContextSize: 128000 },
  { id: 'kimi', label: 'Kimi (Moonshot)', hint: 'api.moonshot.ai', provider: 'custom', baseUrl: 'https://api.moonshot.ai/v1', defaultModel: 'kimi-k2.6', apiMode: 'chat_completions', suggestedModels: ['kimi-k2.6','kimi-k2.5','kimi-k2-thinking','kimi-for-coding'], suggestedContextSize: 256000 },
  { id: 'kimi-cn', label: 'Kimi (Moonshot 中国)', hint: 'api.moonshot.cn', provider: 'custom', baseUrl: 'https://api.moonshot.cn/v1', defaultModel: 'kimi-k2.6', apiMode: 'chat_completions', suggestedModels: ['kimi-k2.6','kimi-k2.5','kimi-k2-thinking','kimi-for-coding'], suggestedContextSize: 256000 },
  { id: 'kimi-coding', label: 'Kimi (Coding Plan)', hint: 'api.kimi.com', provider: 'custom', baseUrl: 'https://api.kimi.com/coding/', defaultModel: 'kimi-for-coding', apiMode: 'chat_completions', suggestedModels: ['kimi-for-coding'], suggestedContextSize: 256000 },
  { id: 'zhipu', label: '智谱 GLM (Zhipu)', hint: 'open.bigmodel.cn', provider: 'custom', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', defaultModel: 'glm-4.6', apiMode: 'chat_completions', suggestedModels: ['glm-5.1','glm-5-turbo','glm-5','glm-5v-turbo','glm-4.7','glm-4.7-flash','glm-4.6','glm-4.6v','glm-4.5','glm-4.5v','glm-4.5-air','glm-4.5-flash'], suggestedContextSize: 128000 },
  { id: 'minimax-global', label: 'MiniMax (Global)', hint: 'api.minimax.io/anthropic', provider: 'custom', baseUrl: 'https://api.minimax.io/anthropic', defaultModel: 'MiniMax-M3', apiMode: 'anthropic_messages', suggestedModels: ['MiniMax-M3','MiniMax-M2.7'], suggestedContextSize: 200000 },
  { id: 'minimax-cn', label: 'MiniMax (中国)', hint: 'api.minimaxi.com/anthropic', provider: 'custom', baseUrl: 'https://api.minimaxi.com/anthropic', defaultModel: 'MiniMax-M3', apiMode: 'anthropic_messages', suggestedModels: ['MiniMax-M3','MiniMax-M2.7'], suggestedContextSize: 200000 },
  { id: 'bailian-coding', label: '阿里百炼 Coding Plan', hint: 'coding.dashscope.aliyuncs.com', provider: 'custom', baseUrl: 'https://coding.dashscope.aliyuncs.com/v1', apiMode: 'chat_completions', defaultModel: 'qwen3.6-plus', suggestedModels: ['qwen3.6-plus','kimi-k2.5','glm-5','MiniMax-M2.5','qwen3.5-plus','qwen3-coder-plus','qwen3-coder-next','glm-4.7'], suggestedContextSize: 131072 },
  { id: 'xiaomi-mimo', label: '小米 MiMo', hint: 'api.xiaomimimo.com', provider: 'custom', baseUrl: 'https://api.xiaomimimo.com/v1', apiMode: 'chat_completions', defaultModel: 'mimo-v2.5-pro', suggestedModels: ['mimo-v2.5-pro','mimo-v2.5','mimo-v2-flash','mimo-v2-pro','mimo-v2-omni'], suggestedContextSize: 1000000 },
  { id: 'volcengine-ark', label: '火山引擎 Ark', hint: 'ark.cn-beijing.volces.com', provider: 'custom', baseUrl: 'https://ark.cn-beijing.volces.com/api/coding/v3', apiMode: 'chat_completions', defaultModel: 'Doubao-Seed-2.0-Code', suggestedModels: ['Doubao-Seed-2.0-Code','Doubao-Seed-2.0-pro','Doubao-Seed-2.0-lite','Doubao-Seed-Code','MiniMax-M2.5','Kimi-K2.5','GLM-4.7','DeepSeek-V3'], suggestedContextSize: 128000 },
  { id: 'ollama-local', label: 'Ollama (本地)', hint: '自托管 llama.cpp / Ollama', provider: 'ollama', baseUrl: 'http://localhost:11434', suggestedContextSize: 32768 },
  { id: 'ollama-cloud', label: 'Ollama Cloud', hint: 'ollama.com', provider: 'custom', baseUrl: 'https://ollama.com/v1', apiMode: 'chat_completions', suggestedModels: ['gpt-oss:120b','gpt-oss:20b','qwen3-coder:480b','kimi-k2:1t','deepseek-v3.1:671b'], suggestedContextSize: 128000 },
  { id: 'custom', label: 'Custom', hint: '任意 OpenAI/Anthropic 兼容端点', provider: 'custom', apiMode: 'chat_completions' },
]

// 预设分组
const presetGroups = [
  { label: '官方 API', items: LLM_PRESETS.filter(p => ['anthropic','openai','google','azure'].includes(p.id)) },
  { label: '国内平台', items: LLM_PRESETS.filter(p => ['deepseek','siliconflow','siliconflow-vision','kimi','kimi-cn','kimi-coding','zhipu','minimax-cn','bailian-coding','xiaomi-mimo','volcengine-ark'].includes(p.id)) },
  { label: '国际平台', items: LLM_PRESETS.filter(p => ['atlascloud','groq','xai','nvidia-nim','minimax-global','ollama-cloud'].includes(p.id)) },
  { label: '本地部署', items: LLM_PRESETS.filter(p => ['claude-code-cli','codex-cli','ollama-local'].includes(p.id)) },
  { label: '自定义', items: LLM_PRESETS.filter(p => p.id === 'custom') },
]

const selectedPresetId = ref('')

// 当前预设对应的推荐模型
const currentSuggestedModels = computed(() => {
  if (selectedPresetId.value) {
    const preset = LLM_PRESETS.find(p => p.id === selectedPresetId.value)
    if (preset?.suggestedModels) return preset.suggestedModels
  }
  // 回退到provider默认模型
  const providerModels: Record<string, string[]> = {
    openai: ['gpt-4o','gpt-4o-mini','gpt-4.1','gpt-4.1-mini','o3','o3-mini','gpt-4-turbo'],
    anthropic: ['claude-sonnet-4-5-20250929','claude-opus-4-5-20251101','claude-3-5-sonnet-20241022'],
    google: ['gemini-2.5-pro','gemini-2.5-flash','gemini-2.0-flash','gemini-1.5-pro'],
    siliconflow: ['Qwen/Qwen2.5-72B-Instruct','deepseek-ai/DeepSeek-V3','deepseek-ai/DeepSeek-R1','Qwen/Qwen2.5-7B-Instruct'],
    ollama: ['llama3.1','qwen2.5','deepseek-r1','mistral','phi3'],
  }
  return providerModels[llmForm.provider] || []
})

const defaultBaseUrl = computed(() =>
  ({ ollama: 'http://localhost:11434', custom: 'https://your-api.com/v1', azure: 'https://your-resource.openai.azure.com', siliconflow: 'https://api.siliconflow.cn/v1' })[llmForm.provider] || ''
)

function providerType(p: string): '' | 'success' | 'warning' | 'danger' {
  return ({ openai: 'success', anthropic: 'warning', google: '', ollama: 'danger', azure: 'info', custom: '' } as any)[p] || ''
}

function applyPreset(presetId: string) {
  if (!presetId) return
  const preset = LLM_PRESETS.find(p => p.id === presetId)
  if (!preset) return
  // 映射provider：将llm_wiki特有的provider映射到我们支持的6种
  const providerMap: Record<string, string> = {
    'claude-code': 'custom', 'codex-cli': 'custom'
  }
  llmForm.provider = providerMap[preset.provider] || preset.provider
  llmForm.name = preset.label
  if (preset.defaultModel) llmForm.model = preset.defaultModel
  if (preset.baseUrl) llmForm.baseUrl = preset.baseUrl
  if (preset.apiMode) llmForm.apiMode = preset.apiMode
  if (preset.suggestedContextSize) llmForm.maxContextSize = preset.suggestedContextSize
}

function onProviderChange() {
  llmForm.model = currentSuggestedModels.value[0] || ''
}

async function loadLlmConfigs() {
  try {
    const res = await getLlmConfigs()
    llmConfigs.value = res.data || []
  } catch (e) { console.error(e) }
}

async function saveLlm() {
  try {
    if (editingLlm.value) {
      await updateLlmConfig(editingLlm.value.id, llmForm)
    } else {
      await createLlmConfig(llmForm)
    }
    ElMessage.success('保存成功')
    showAddLlm.value = false
    editingLlm.value = null
    Object.assign(llmForm, { name: '', provider: 'openai', apiKey: '', model: '', baseUrl: '', purpose: 'chat', maxContextSize: 4096, apiMode: 'chat_completions', description: '' })
    selectedPresetId.value = ''
    loadLlmConfigs()
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  }
}

function editLlm(row: any) {
  editingLlm.value = row
  Object.assign(llmForm, {
    name: row.name, provider: row.provider, apiKey: row.api_key || '',
    model: row.model, baseUrl: row.base_url || '', purpose: row.purpose,
    maxContextSize: row.max_context_size || 4096, apiMode: row.api_mode || 'chat_completions',
    description: row.description || ''
  })
  showAddLlm.value = true
}

async function toggleLlm(id: number) {
  await toggleLlmConfig(id)
  loadLlmConfigs()
}

async function deleteLlmConfig(id: number) {
  await ElMessageBox.confirm('确定删除该配置？', '提示', { type: 'warning' })
  await deleteLlmConfigApi(id)
  ElMessage.success('删除成功')
  loadLlmConfigs()
}

async function testConnection(row: any) {
  testingId.value = row.id
  try {
    const res = await testLlmConnection(row)
    const data = res.data
    if (data.success) {
      ElMessage.success(data.message || '连接测试成功')
    } else {
      ElMessage.error(data.message || '连接测试失败')
    }
  } catch (e: any) {
    ElMessage.error('测试失败: ' + (e.response?.data?.message || e.message || '网络请求异常'))
  } finally {
    testingId.value = null
  }
}

// OCR 配置（参考 llm_wiki 的 multimodal-section.tsx）
const ocrForm = reactive({
  enabled: false,
  useMainLlm: true,
  provider: 'custom',
  apiKey: '',
  model: '',
  baseUrl: '',
  apiMode: 'chat_completions',
  concurrency: 2,
})
const ocrPresetId = ref('')
const ocrTesting = ref(false)

// OCR 专用预设（只包含视觉模型）
const OCR_PRESETS: LlmPreset[] = [
  { id: 'siliconflow-vlm', label: 'SiliconFlow VLM', hint: 'api.siliconflow.cn', provider: 'siliconflow', baseUrl: 'https://api.siliconflow.cn/v1', defaultModel: 'Pro/Qwen/Qwen2-VL-7B-Instruct', apiMode: 'chat_completions', suggestedModels: ['Pro/Qwen/Qwen2-VL-7B-Instruct','Qwen/Qwen2-VL-72B-Instruct','OpenGVLab/InternVL2-26B','Pro/OpenGVLab/InternVL2-8B','deepseek-ai/deepseek-vl2'] },
  { id: 'openai-vision', label: 'OpenAI GPT-4o', hint: '官方 OpenAI', provider: 'openai', defaultModel: 'gpt-4o', suggestedModels: ['gpt-4o','gpt-4o-mini','gpt-4.1','gpt-4.1-mini'] },
  { id: 'google-vision', label: 'Google Gemini', hint: '官方 Gemini', provider: 'google', defaultModel: 'gemini-2.5-flash', suggestedModels: ['gemini-2.5-pro','gemini-2.5-flash','gemini-2.0-flash'] },
  { id: 'zhipu-vlm', label: '智谱 GLM 视觉', hint: 'open.bigmodel.cn', provider: 'custom', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', defaultModel: 'glm-4.6v', apiMode: 'chat_completions', suggestedModels: ['glm-5v-turbo','glm-4.6v','glm-4.5v','glm-4v-plus'] },
  { id: 'ollama-vlm', label: 'Ollama 本地视觉', hint: 'localhost:11434', provider: 'ollama', baseUrl: 'http://localhost:11434', defaultModel: 'llava', suggestedModels: ['llava','llama3.2-vision','bakllava','moondream','qwen2-vl'] },
  { id: 'custom-vlm', label: '自定义', hint: '任意兼容端点', provider: 'custom', apiMode: 'chat_completions' },
]

const ocrPresetGroups = [
  { label: '国内平台', items: OCR_PRESETS.filter(p => ['siliconflow-vlm','zhipu-vlm'].includes(p.id)) },
  { label: '国际平台', items: OCR_PRESETS.filter(p => ['openai-vision','google-vision'].includes(p.id)) },
  { label: '本地部署', items: OCR_PRESETS.filter(p => ['ollama-vlm'].includes(p.id)) },
  { label: '自定义', items: OCR_PRESETS.filter(p => ['custom-vlm'].includes(p.id)) },
]

const ocrSuggestedModels = computed(() => {
  if (ocrPresetId.value) {
    const preset = OCR_PRESETS.find(p => p.id === ocrPresetId.value)
    if (preset?.suggestedModels) return preset.suggestedModels
  }
  const providerModels: Record<string, string[]> = {
    openai: ['gpt-4o','gpt-4o-mini','gpt-4.1','gpt-4.1-mini'],
    anthropic: ['claude-sonnet-4-5-20250929','claude-3-5-sonnet-20241022'],
    google: ['gemini-2.5-pro','gemini-2.5-flash','gemini-2.0-flash'],
    siliconflow: ['Pro/Qwen/Qwen2-VL-7B-Instruct','Qwen/Qwen2-VL-72B-Instruct','OpenGVLab/InternVL2-26B','deepseek-ai/deepseek-vl2'],
    ollama: ['llava','llama3.2-vision','bakllava','moondream'],
    custom: ['Pro/Qwen/Qwen2-VL-7B-Instruct','Qwen/Qwen2-VL-72B-Instruct','glm-4.6v'],
  }
  return providerModels[ocrForm.provider] || []
})

function applyOcrPreset(presetId: string) {
  if (!presetId) return
  const preset = OCR_PRESETS.find(p => p.id === presetId)
  if (!preset) return
  const providerMap: Record<string, string> = {
    'claude-code': 'custom', 'codex-cli': 'custom'
  }
  ocrForm.provider = providerMap[preset.provider] || preset.provider
  if (preset.defaultModel) ocrForm.model = preset.defaultModel
  if (preset.baseUrl) ocrForm.baseUrl = preset.baseUrl
  if (preset.apiMode) ocrForm.apiMode = preset.apiMode
}

function onOcrProviderChange() {
  ocrForm.model = ocrSuggestedModels.value[0] || ''
}

async function loadOcrConfig() {
  try {
    const res = await getSettings()
    const settings = res.data || {}
    if (settings.ocrConfig) {
      try {
        const parsed = JSON.parse(settings.ocrConfig)
        Object.assign(ocrForm, parsed)
      } catch {
        // 如果解析失败，可能是旧格式
        Object.assign(ocrForm, settings.ocrConfig)
      }
    }
  } catch (e) { console.error(e) }
}

async function saveOcrConfig() {
  try {
    await updateSettings({ ocrConfig: JSON.stringify(ocrForm) })
    ElMessage.success('OCR 配置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  }
}

async function testOcrConnection() {
  // 优先查找 purpose=ocr 的已启用配置（全局设置）
  const ocrConfig = llmConfigs.value.find((c: any) => c.enabled && c.purpose === 'ocr')

  if (ocrConfig) {
    // 有专门的 OCR 配置，用它测试
    ocrTesting.value = true
    try {
      const res = await testLlmConnection(ocrConfig)
      res.data.success
        ? ElMessage.success('OCR 连接成功: ' + res.data.message)
        : ElMessage.error('OCR 连接失败: ' + res.data.message)
    } catch (e: any) {
      ElMessage.error('测试失败: ' + (e.message || ''))
    }
    ocrTesting.value = false
    return
  }

  if (ocrForm.useMainLlm) {
    // 复用主 LLM，查找有有效 baseUrl 的 chat 配置
    const chatConfig = llmConfigs.value.find(
      (c: any) => c.enabled && (c.purpose === 'chat' || c.purpose === 'both') && c.base_url
    )
    if (!chatConfig) {
      ElMessage.warning('未找到可用的对话 LLM 配置（需要有效的 Base URL），请在「大语言模型」中配置')
      return
    }
    ocrTesting.value = true
    try {
      const res = await testLlmConnection(chatConfig)
      res.data.success
        ? ElMessage.success('OCR (复用主LLM) 连接成功: ' + res.data.message)
        : ElMessage.error('OCR 连接失败: ' + res.data.message)
    } catch (e: any) {
      ElMessage.error('测试失败: ' + (e.message || ''))
    }
    ocrTesting.value = false
    return
  }

  // 使用 OCR 表单中的配置
  if (!ocrForm.apiKey) {
    ElMessage.warning('请先填写 API Key')
    return
  }
  if (!ocrForm.model) {
    ElMessage.warning('请先选择模型')
    return
  }

  ocrTesting.value = true
  try {
    const testConfig = {
      provider: ocrForm.provider,
      apiKey: ocrForm.apiKey,
      model: ocrForm.model,
      baseUrl: ocrForm.baseUrl || (ocrForm.provider === 'siliconflow' ? 'https://api.siliconflow.cn/v1' : ''),
      apiMode: ocrForm.apiMode || 'chat_completions',
    }
    const res = await testLlmConnection(testConfig)
    res.data.success
      ? ElMessage.success('OCR 连接成功: ' + res.data.message)
      : ElMessage.error('OCR 连接失败: ' + res.data.message)
  } catch (e: any) {
    ElMessage.error('测试失败: ' + (e.message || ''))
  }
  ocrTesting.value = false
}

// Embedding（参考 llm_wiki 的 embedding-section.tsx 和 embedding.ts）
const embeddingConfig = reactive({
  provider: 'openai', model: 'text-embedding-3-small', apiKey: '',
  baseUrl: '', dimensions: 1536
})

const embeddingModels = computed(() => {
  return ({
    openai: ['text-embedding-3-small', 'text-embedding-3-large', 'text-embedding-ada-002', 'text-embedding-004'],
    ollama: ['nomic-embed-text', 'mxbai-embed-large', 'all-minilm'],
    local: ['all-MiniLM-L6-v2', 'bge-small-zh-v1.5', 'bge-large-zh-v1.5'],
    custom: [
      // llm_wiki EMBEDDING_MODEL_SUGGESTIONS
      'text-embedding-3-small', 'text-embedding-3-large',
      'gemini-embedding-001', 'gemini-embedding-2',
      'text-embedding-004',
      'doubao-embedding-vision', 'doubao-embedding-text-240715',
      'text-embedding-qwen3-embedding-0.6b',
      'nomic-embed-text', 'mxbai-embed-large',
      // SiliconFlow Embedding
      'BAAI/bge-m3', 'BAAI/bge-large-zh-v1.5', 'BAAI/bge-large-en-v1.5',
      'netease-youdao/bce-embedding-base_v1', 'Pro/BAAI/bge-m3',
    ],
  } as any)[embeddingConfig.provider] || []
})

async function saveEmbedding() {
  try {
    await updateSettings({
      embedding_provider: embeddingConfig.provider,
      embedding_model: embeddingConfig.model,
      embedding_api_key: embeddingConfig.apiKey,
      embedding_dimensions: String(embeddingConfig.dimensions),
    })
    ElMessage.success('Embedding 配置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  }
}

async function loadEmbedding() {
  try {
    const res = await getSettings()
    const data = res.data || {}
    if (data.embedding_provider) embeddingConfig.provider = data.embedding_provider
    if (data.embedding_model) embeddingConfig.model = data.embedding_model
    if (data.embedding_api_key) embeddingConfig.apiKey = data.embedding_api_key
    if (data.embedding_dimensions) embeddingConfig.dimensions = parseInt(data.embedding_dimensions) || 1536
  } catch (e) { console.error(e) }
}

async function testEmbedding() {
  try {
    const res = await testLlmConnection({
      name: 'Embedding Test', provider: embeddingConfig.provider, apiKey: embeddingConfig.apiKey,
      model: embeddingConfig.model, baseUrl: '', purpose: 'embedding',
      maxContextSize: 4096, apiMode: 'chat_completions'
    })
    const d = res.data
    d.success ? ElMessage.success('Embedding 连接测试通过') : ElMessage.error('Embedding 连接测试失败: ' + d.message)
  } catch (e: any) {
    ElMessage.error('测试失败: ' + (e.message || ''))
  }
}

// Search
const searchConfig = reactive({
  provider: 'duckduckgo', apiKey: '', googleApiKey: '', googleCx: '',
  searxngUrl: '', braveApiKey: '', maxResults: 10
})

const searchKeyPlaceholder = computed(() =>
  ({ tavily: 'tvly-...', serpapi: '从 serpapi.com 获取', brave: '从 brave.com/search/api 获取' } as any)[searchConfig.provider] || ''
)

async function loadSearchConfig() {
  try {
    const res = await getSearchConfigApi()
    const data = res.data || {}
    if (data.provider) searchConfig.provider = data.provider
    if (data.api_key) searchConfig.apiKey = data.api_key
    if (data.google_api_key) searchConfig.googleApiKey = data.google_api_key
    if (data.google_cx) searchConfig.googleCx = data.google_cx
    if (data.searxng_url) searchConfig.searxngUrl = data.searxng_url
    if (data.brave_api_key) searchConfig.braveApiKey = data.brave_api_key
    if (data.max_results) searchConfig.maxResults = parseInt(data.max_results) || 10
  } catch (e) { console.error(e) }
}

async function saveSearchConfig() {
  try {
    const config: Record<string, string> = {
      provider: searchConfig.provider,
      api_key: searchConfig.apiKey,
      google_api_key: searchConfig.googleApiKey,
      google_cx: searchConfig.googleCx,
      searxng_url: searchConfig.searxngUrl,
      brave_api_key: searchConfig.braveApiKey,
      max_results: String(searchConfig.maxResults),
    }
    await saveSearchConfigApi(config)
    ElMessage.success('搜索引擎配置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  }
}

const testingSearch = ref(false)
async function testSearch() {
  testingSearch.value = true
  try {
    const res = await testSearchConfigApi()
    const d = res.data
    d.success ? ElMessage.success(d.message) : ElMessage.error(d.message)
  } catch (e: any) {
    ElMessage.error('搜索测试失败: ' + (e.message || ''))
  }
  testingSearch.value = false
}

// General Settings - 用户友好的结构化表单
const generalForm = reactive({
  system_name: '智能情报分析平台',
  page_size: 20,
  max_upload_size: 100,
  auto_approve_entries: false,
  qa_max_entries: 10,
  qa_show_sources: true,
  qa_image_threshold: 0.6,
  qa_image_topK: 5,
  qa_table_threshold: 0.6,
  qa_table_topK: 3,
  research_query_count: 5,
  research_max_results_per_query: 10,
  upload_dir: '',
  language: 'zh',
  theme: 'light',
})
const loadingGeneral = ref(false)

async function loadGeneral() {
  loadingGeneral.value = true
  try {
    const res = await getSettings()
    const data = res.data || {}
    if (data.system_name) generalForm.system_name = data.system_name
    if (data.page_size) generalForm.page_size = parseInt(data.page_size) || 20
    if (data.max_upload_size) generalForm.max_upload_size = parseInt(data.max_upload_size) || 100
    if (data.auto_approve_entries) generalForm.auto_approve_entries = data.auto_approve_entries === 'true'
    if (data.qa_max_entries) generalForm.qa_max_entries = parseInt(data.qa_max_entries) || 10
    if (data.qa_show_sources) generalForm.qa_show_sources = data.qa_show_sources !== 'false'
    if (data.qa_image_threshold) generalForm.qa_image_threshold = parseFloat(data.qa_image_threshold) || 0.6
    if (data.qa_image_topK) generalForm.qa_image_topK = parseInt(data.qa_image_topK) || 5
    if (data.qa_table_threshold) generalForm.qa_table_threshold = parseFloat(data.qa_table_threshold) || 0.6
    if (data.qa_table_topK) generalForm.qa_table_topK = parseInt(data.qa_table_topK) || 3
    if (data.research_query_count) generalForm.research_query_count = parseInt(data.research_query_count) || 5
    if (data.research_max_results_per_query) generalForm.research_max_results_per_query = parseInt(data.research_max_results_per_query) || 10
    if (data.upload_dir) generalForm.upload_dir = data.upload_dir
    if (data.language) generalForm.language = data.language
    if (data.theme) generalForm.theme = data.theme
  } catch (e) { console.error(e) }
  loadingGeneral.value = false
}

async function saveGeneral() {
  try {
    const kvData: Record<string, string> = {
      system_name: generalForm.system_name,
      page_size: String(generalForm.page_size),
      max_upload_size: String(generalForm.max_upload_size),
      auto_approve_entries: String(generalForm.auto_approve_entries),
      qa_max_entries: String(generalForm.qa_max_entries),
      qa_show_sources: String(generalForm.qa_show_sources),
      qa_image_threshold: String(generalForm.qa_image_threshold),
      qa_image_topK: String(generalForm.qa_image_topK),
      qa_table_threshold: String(generalForm.qa_table_threshold),
      qa_table_topK: String(generalForm.qa_table_topK),
      research_query_count: String(generalForm.research_query_count),
      research_max_results_per_query: String(generalForm.research_max_results_per_query),
      upload_dir: generalForm.upload_dir,
      language: generalForm.language,
      theme: generalForm.theme,
    }
    await updateSettings(kvData)
    ElMessage.success('通用设置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || '未知错误'))
  }
}

// KG Config
const kgConfig = reactive({
  extractionStrategy: 'llm',
  edgeTypes: ['belongs_to', 'cites', 'supports', 'related_to'],
  communityAlgorithm: 'louvain',
  minCommunitySize: 3,
})

async function saveKgConfig() {
  try {
    await updateSettings({
      kg_extraction_strategy: kgConfig.extractionStrategy,
      kg_edge_types: JSON.stringify(kgConfig.edgeTypes),
      kg_community_algorithm: kgConfig.communityAlgorithm,
    })
    ElMessage.success('图谱构建配置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  }
}

async function loadKgConfig() {
  try {
    const res = await getSettings()
    const data = res.data || {}
    if (data.kg_extraction_strategy) kgConfig.extractionStrategy = data.kg_extraction_strategy
    if (data.kg_edge_types) {
      try { kgConfig.edgeTypes = JSON.parse(data.kg_edge_types) } catch { /* ignore */ }
    }
    if (data.kg_community_algorithm) kgConfig.communityAlgorithm = data.kg_community_algorithm
  } catch (e) { console.error(e) }
}

const rebuilding = ref(false)
async function rebuildGraph() {
  rebuilding.value = true
  try {
    const res = await buildKGGraph()
    const d = res.data
    ElMessage.success(`${d.message}：${d.node_count} 节点、${d.edge_count} 关系、${d.community_count} 社区`)
  } catch (e: any) {
    ElMessage.error('图谱重建失败: ' + (e.message || '未知错误'))
  }
  rebuilding.value = false
}

// 向量索引
const rebuildingVector = ref(false)
const vectorStats = reactive({ size: 0, dimension: 0, hasEmbeddingConfig: false })

async function loadVectorStats() {
  try {
    const res = await getVectorStats()
    Object.assign(vectorStats, res.data)
  } catch (e) { console.error(e) }
}

async function rebuildVectorIdx() {
  rebuildingVector.value = true
  try {
    const res = await rebuildVectorIndex()
    const d = res.data
    ElMessage.success(`向量索引重建完成：${d.indexed_count} 个词条已索引`)
    await loadVectorStats()
  } catch (e: any) {
    ElMessage.error('向量索引重建失败: ' + (e.message || '未知错误'))
  }
  rebuildingVector.value = false
}

// 桌面端设置 state
const desktopForm = reactive({
  minimizeToTray: true,
  closeAction: 'minimize', // 'minimize' or 'quit'
  checkUpdateFrequency: 'daily',
  customDataDir: ''
})

const logs = ref<string[]>([])
const loadingLogs = ref(false)

import { isTauri, openDirectory } from '../../composables/useTauri'

async function handleOpenDataDir() {
  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      await invoke('open_data_dir')
      ElMessage.success('正在打开数据目录...')
    } catch (e: any) {
      ElMessage.error('无法打开数据目录: ' + e.message)
    }
  } else {
    ElMessage.warning('非桌面环境下无法打开数据目录')
  }
}

async function handleSelectSettingsDir() {
  const dir = await openDirectory()
  if (dir) {
    desktopForm.customDataDir = dir
  }
}

function handleCheckUpdate() {
  const event = new CustomEvent('tauri-check-update', { detail: { manual: true } })
  window.dispatchEvent(event)
}

async function loadDesktopConfigs() {
  const saved = localStorage.getItem('desktopSettings')
  if (saved) {
    try {
      const parsed = JSON.parse(saved)
      Object.assign(desktopForm, parsed)
    } catch (e) {
      console.error('Failed to parse desktop settings:', e)
    }
  }

  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      const rustConfig: any = await invoke('get_desktop_config')
      if (rustConfig) {
        desktopForm.customDataDir = rustConfig.custom_data_dir || ''
        desktopForm.closeAction = rustConfig.close_action || 'minimize'
      }
    } catch (e) {
      console.error('Failed to load desktop config from Rust:', e)
    }
  }
  
  // 首次加载也拉取日志
  fetchLogs()
}

async function saveDesktopConfig() {
  localStorage.setItem('desktopSettings', JSON.stringify(desktopForm))
  
  if (isTauri()) {
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      // 检查路径是否更改
      const rustConfig: any = await invoke('get_desktop_config')
      const oldDir = rustConfig?.custom_data_dir || ''
      const newDir = desktopForm.customDataDir || ''
      
      await invoke('save_desktop_config', {
        config: {
          custom_data_dir: newDir,
          close_action: desktopForm.closeAction
        }
      })
      
      if (oldDir !== newDir) {
        ElMessageBox.alert('存储路径已更改！新路径需要重启应用以启动后端数据库和文件服务，请手动重启应用。', '配置更新', {
          confirmButtonText: '确定',
          type: 'warning'
        })
      } else {
        ElMessage.success('桌面设置已保存')
      }
    } catch (e: any) {
      ElMessage.error('保存桌面设置失败: ' + e.message)
    }
  } else {
    ElMessage.success('桌面设置已保存')
  }
}

async function fetchLogs() {
  loadingLogs.value = true
  try {
    const resp = await fetch('/api/admin/logs')
    if (resp.ok) {
      const json = await resp.json()
      if (json.code === 200) {
        logs.value = json.data
      }
    }
  } catch (e) {
    console.error('Failed to fetch logs:', e)
  } finally {
    loadingLogs.value = false
  }
}

// 系统日志
const systemLogs = ref<string[]>([])
const loadingSystemLogs = ref(false)
const showLogDialog = ref(false)

async function fetchSystemLogs() {
  loadingSystemLogs.value = true
  try {
    const resp = await fetch('/api/admin/logs?limit=1000')
    if (resp.ok) {
      const json = await resp.json()
      if (json.code === 200) {
        systemLogs.value = json.data || []
      }
    }
  } catch (e) {
    console.error('Failed to fetch system logs:', e)
  } finally {
    loadingSystemLogs.value = false
  }
}

function openLogViewer() {
  fetchSystemLogs()
  showLogDialog.value = true
}

async function openLogDir() {
  if (isTauri()) {
    try {
      const { openPath } = await import('@tauri-apps/plugin-opener')
      const home = await (await import('@tauri-apps/api/path')).homeDir()
      await openPath(home + '.pku-platform/logs')
      ElMessage.success('正在打开日志目录...')
    } catch (e: any) {
      ElMessage.error('无法打开日志目录: ' + e.message)
    }
  } else {
    ElMessage.warning('非桌面环境下无法打开日志目录')
  }
}

onMounted(() => {
  loadLlmConfigs()
  loadGeneral()
  loadVectorStats()
  loadSearchConfig()
  loadEmbedding()
  loadOcrConfig()
  loadKgConfig()
  loadDesktopConfigs()
})
</script>

<style scoped>
.settings-page { padding: 0; }

.category-nav {
  background: white;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  overflow: hidden;
}

.nav-title {
  padding: 14px 16px;
  font-size: 14px;
  font-weight: bold;
  color: #1E293B;
  border-bottom: 1px solid #E2E8F0;
  background: #F8FAFC;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  border-bottom: 1px solid #F1F5F9;
  transition: all 0.15s;
}

.nav-item:hover { background: #F8FAFC; }
.nav-item.active { background: #EFF6FF; color: #2563EB; font-weight: 600; border-left: 3px solid #3B82F6; }
.nav-icon { font-size: 16px; }

.config-panel {
  background: white;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  min-height: 500px;
}

.config-section {
  padding: 20px;
}

.config-section h3 {
  margin: 0 0 4px;
  font-size: 16px;
  color: #1E293B;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.section-desc {
  font-size: 13px;
  color: #64748B;
  margin-bottom: 16px;
}

.config-form {
  max-width: 700px;
  margin-top: 16px;
}

.model-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.model-chip {
  cursor: pointer;
  transition: all 0.15s;
}

.model-chip:hover {
  background: #4A90D9;
  color: white;
  border-color: #4A90D9;
}

.strategy-desc {
  margin-top: 8px;
  font-size: 12px;
  color: #64748B;
  line-height: 1.6;
}

.desc-item {
  background: #F8FAFC;
  border: 1px solid #E2E8F0;
  border-radius: 4px;
  padding: 8px 12px;
}

.desc-item strong {
  color: #1E293B;
}

.vector-stats {
  font-size: 13px;
  color: #475569;
  display: flex;
  align-items: center;
  gap: 8px;
}

.form-hint {
  font-size: 12px;
  color: #94A3B8;
  line-height: 1.5;
  margin-top: 4px;
}

/* API 接入样式 */
.api-doc-wrap {
  padding: 8px 4px;
}
.api-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
}
.api-method {
  font-size: 11px;
  font-weight: 700;
  padding: 3px 6px;
  border-radius: 4px;
  text-transform: uppercase;
  color: white;
}
.api-method.post {
  background-color: #10b981;
}
.api-method.get {
  background-color: #3b82f6;
}
.api-path {
  font-family: Menlo, Monaco, Consolas, "Courier New", monospace;
  font-size: 13px;
  font-weight: bold;
  background-color: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  color: #0f172a;
}
.api-desc {
  font-size: 13px;
  color: #475569;
  line-height: 1.6;
}
.code-block {
  background-color: #0f172a;
  color: #f8fafc;
  padding: 14px 18px;
  border-radius: 6px;
  font-family: Menlo, Monaco, Consolas, "Courier New", monospace;
  font-size: 12px;
  line-height: 1.5;
  overflow-x: auto;
  margin: 10px 0 16px 0;
}
.code-block code {
  color: inherit;
  background: transparent;
  padding: 0;
}
.action-buttons {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.api-table th {
  border-bottom: 2px solid #e2e8f0;
}
.api-table td {
  border-bottom: 1px solid #f1f5f9;
}
</style>
