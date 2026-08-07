
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

LOCK TABLES `settings` WRITE;
/*!40000 ALTER TABLE `settings` DISABLE KEYS */;
INSERT INTO `settings` (`setting_key`, `value`, `description`) VALUES ('qa_image_enabled','true','问答中是否返回相关图片'),('qa_image_threshold','0.2','问答图片检索最低相似度阈值'),('qa_image_topK','5','问答图片检索最大返回数量'),('qa_stream_enabled','true',NULL),('qa_table_threshold','0.35',NULL),('research_max_results_per_query','10','每个查询最大结果数'),('research_max_sources','20','深度研究最大来源数量'),('search_api_key','','其他搜索API Key'),('search_baidu_api_key','','百度API Key'),('search_baidu_secret_key','','百度Secret Key'),('search_enabled','true','网络搜索开关'),('search_google_api_key','','Google Custom Search API Key'),('search_google_cx','','Google Custom Search CX'),('search_provider','baidu','搜索引擎提供商'),('search_searxng_url','','SearXNG自托管地址'),('upload_allowed_types','pdf,doc,docx,xls,xlsx,ppt,pptx,txt,md,markdown,csv,jpg,jpeg,png,gif,bmp,webp','允许上传的文件类型'),('upload_max_file_size','104857600','上传文件最大大小（字节）');
/*!40000 ALTER TABLE `settings` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `llm_configs` WRITE;
/*!40000 ALTER TABLE `llm_configs` DISABLE KEYS */;
INSERT INTO `llm_configs` (`id`, `name`, `provider`, `api_key`, `model`, `base_url`, `enabled`, `purpose`, `max_context_size`, `api_mode`, `azure_api_version`, `created_at`, `description`) VALUES (74,'SiliconFlow-Chat','siliconflow',NULL,'Qwen/Qwen2.5-72B-Instruct','https://api.siliconflow.cn/v1',0,'chat',32768,NULL,NULL,'2026-07-07 12:22:50','SiliconFlow: 主对话LLM，Qwen2.5-72B'),(75,'SiliconFlow-Extract','siliconflow',NULL,'Qwen/Qwen2.5-72B-Instruct','https://api.siliconflow.cn/v1',0,'extract',32768,NULL,NULL,'2026-07-07 12:22:50','SiliconFlow: 词条抽取专用LLM'),(76,'SiliconFlow-VLM','siliconflow',NULL,'Qwen/Qwen3-VL-8B-Thinking','https://api.siliconflow.cn/v1',0,'vlm',4096,NULL,NULL,'2026-07-07 12:22:50','SiliconFlow: 视觉语言模型，处理图片/表格'),(77,'SiliconFlow-Embedding','siliconflow',NULL,'BAAI/bge-m3','https://api.siliconflow.cn/v1',0,'embedding',0,NULL,NULL,'2026-07-07 12:22:50','SiliconFlow: 中文Embedding模型'),(78,'SiliconFlow-Rerank','siliconflow',NULL,'BAAI/bge-reranker-v2-m3','https://api.siliconflow.cn/v1',0,'rerank',0,NULL,NULL,'2026-07-07 12:22:50','SiliconFlow: Rerank重排序模型'),(79,'DeepSeek-V3','deepseek',NULL,'deepseek-chat','https://api.deepseek.com',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','深度求索V3模型，高性价比'),(80,'DeepSeek-R1','deepseek',NULL,'deepseek-reasoner','https://api.deepseek.com',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','深度求索R1推理模型'),(81,'通义千问-Max','qwen',NULL,'qwen-max','https://dashscope.aliyuncs.com/compatible-mode/v1',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','阿里云通义千问旗舰模型'),(82,'通义千问-Plus','qwen',NULL,'qwen-plus','https://dashscope.aliyuncs.com/compatible-mode/v1',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','阿里云通义千问增强模型'),(83,'Moonshot-128k','moonshot',NULL,'moonshot-v1-128k','https://api.moonshot.cn/v1',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','月之暗面Kimi 128K上下文'),(84,'MiniMax-Text-01','minimax',NULL,'MiniMax-Text-01','https://api.minimax.chat/v1',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','MiniMax旗舰模型'),(85,'混元-Pro','hunyuan',NULL,'hunyuan-pro','https://api.hunyuan.cloud.tencent.com/v1',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','腾讯混元旗舰模型'),(86,'豆包-Pro','doubao',NULL,'doubao-pro-32k','https://ark.cn-beijing.volces.com/api/v3',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','字节跳动豆包Pro 32K模型'),(87,'GPT-4o','openai',NULL,'gpt-4o','https://api.openai.com/v1',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','OpenAI GPT-4o旗舰模型'),(88,'Claude-3.5-Sonnet','anthropic',NULL,'claude-3-5-sonnet-20241022','https://api.anthropic.com/v1',0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','Anthropic Claude 3.5 Sonnet'),(89,'Gemini-1.5-Pro','google',NULL,'gemini-1.5-pro',NULL,0,'chat',4096,NULL,NULL,'2026-07-07 12:22:50','Google Gemini 1.5 Pro'),(99,'通义Embedding','qwen',NULL,'text-embedding-v3','https://dashscope.aliyuncs.com/compatible-mode/v1',0,'embedding',4096,NULL,NULL,'2026-07-07 13:56:55','阿里云通义文本向量模型'),(101,'Moonshot-32k','moonshot',NULL,'moonshot-v1-32k','https://api.moonshot.cn/v1',0,'chat',4096,NULL,NULL,'2026-07-07 13:56:55','月之暗面Kimi 32K上下文'),(103,'Step-2-16K','stepfun',NULL,'step-2-16k','https://api.stepfun.com/v1',0,'chat',4096,NULL,NULL,'2026-07-07 13:56:55','阶跃星辰Step-2模型'),(105,'混元-Standard','hunyuan',NULL,'hunyuan-standard','https://api.hunyuan.cloud.tencent.com/v1',0,'chat',4096,NULL,NULL,'2026-07-07 13:56:55','腾讯混元标准模型'),(107,'豆包-Lite','doubao',NULL,'doubao-lite-32k','https://ark.cn-beijing.volces.com/api/v3',0,'chat',4096,NULL,NULL,'2026-07-07 13:56:55','字节跳动豆包Lite 32K模型'),(109,'GPT-4o-mini','openai',NULL,'gpt-4o-mini','https://api.openai.com/v1',0,'chat',4096,NULL,NULL,'2026-07-07 13:56:55','OpenAI GPT-4o-mini'),(110,'OpenAI-Embedding-Large','openai',NULL,'text-embedding-3-large','https://api.openai.com/v1',0,'embedding',4096,NULL,NULL,'2026-07-07 13:56:55','OpenAI文本向量模型'),(113,'Gemini-1.5-Flash','google',NULL,'gemini-1.5-flash',NULL,0,'chat',4096,NULL,NULL,'2026-07-07 13:56:55','Google Gemini 1.5 Flash'),(114,'SiliconFlow Qwen2.5','siliconflow',NULL,'Qwen/Qwen2.5-72B-Instruct','https://api.siliconflow.cn/v1',0,'chat',4096,NULL,NULL,'2026-07-07 16:20:45',NULL),(115,'SiliconFlow-OCR','siliconflow',NULL,'Qwen/Qwen3-VL-8B-Thinking','https://api.siliconflow.cn/v1',0,'ocr',4096,NULL,NULL,'2026-07-07 22:48:17','PaddleOCR-VL 专用表格识别模型'),(116,'SiliconFlow Qwen2.5','siliconflow',NULL,'Qwen/Qwen2.5-72B-Instruct','https://api.siliconflow.cn/v1',0,'chat',4096,NULL,NULL,'2026-07-08 09:20:37',NULL);
/*!40000 ALTER TABLE `llm_configs` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

