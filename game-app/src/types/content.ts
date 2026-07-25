// 游戏附加应用的内容数据结构定义
// 三大板块：世界观(worldview) / NPC人物设定(npcs) / 游戏流程介绍(flow)

/** 世界观中的一个小节 */
export interface WorldviewBlock {
  /** 小节标题，例如「时代背景」「核心冲突」 */
  heading: string;
  /** 小节正文，支持多段，用数组表示段落 */
  paragraphs: string[];
}

/** 世界观板块 */
export interface Worldview {
  /** 世界/作品名称 */
  title: string;
  /** 一句话副标题或标语 */
  tagline: string;
  /** 概述段落 */
  intro: string[];
  /** 展开的世界观小节 */
  blocks: WorldviewBlock[];
  /** 关键词标签，用于快速呈现世界基调 */
  keywords: string[];
  /** 联系方式，每行一条（展示在首页底部） */
  contact?: string[];
}

/** 单个 NPC 人物设定 */
export interface Npc {
  /** 唯一 id，用作列表 key 与 data-testid */
  id: string;
  /** 角色名 */
  name: string;
  /** 身份 / 职务 / 阵营 */
  role: string;
  /** 一句话人物标语 */
  tagline: string;
  /** 人物详细设定，按段落 */
  description: string[];
  /** 性格 / 能力 / 特征标签 */
  traits: string[];
  /** 代表台词（可选） */
  quote?: string;
  /** 头像图片的 import 结果（可选）。注意：图片必须先 import 再传入，禁止字符串路径 */
  avatar?: string;
}

/** 游戏流程中的一个步骤 */
export interface FlowStep {
  /** 步骤序号，从 1 开始 */
  order: number;
  /** 步骤标题 */
  title: string;
  /** 步骤说明，按段落 */
  description: string[];
  /** 小贴士 / 提示（可选） */
  tip?: string;
}

/** 游戏流程板块 */
export interface GameFlow {
  /** 板块引导语 */
  intro: string[];
  /** 流程步骤列表 */
  steps: FlowStep[];
}

/** 应用完整内容 */
export interface GameContent {
  /** 应用标题（用于顶部 & 导航栏） */
  appTitle: string;
  worldview: Worldview;
  npcs: Npc[];
  flow: GameFlow;
}

/** 板块标识：首页启动页 + 三大板块（游戏流程已替换为 AI 秘书对话） */
export type SectionKey = 'home' | 'worldview' | 'npcs' | 'secretary';

/** AI 秘书对话中的一条消息 */
export interface ChatMessage {
  /** 唯一 id，用作列表 key */
  id: string;
  /** 发送方：玩家 或 秘书 */
  role: 'user' | 'secretary';
  /** 文本内容 */
  text: string;
  /** 是否正在流式生成中（可选） */
  pending?: boolean;
}
