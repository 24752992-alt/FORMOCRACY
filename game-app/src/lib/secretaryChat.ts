// AI 秘书对话逻辑：封装灵光宿主的流式 LLM 能力，并提供多级降级兜底。
// 调用优先级：lingguang.ai.llmStream（流式） → callLLM（一次性） → 本地兜底台词（预览环境）。
import type { ChatMessage } from '@/types/content';

/** 秘书的人设与世界观锚定，作为 system prompt 注入。 */
export const SECRETARY_SYSTEM_PROMPT = [
  '你是「秘书」，衡川市第十二区行政窗口的接待秘书，外形是一只穿着制服的猫。',
  '你的职责是引导刚入职的行政窗口受理员（也就是正在和你对话的「玩家」）熟悉这座城市的制度、证明资料与受理流程。',
  '世界观要点：这是一座以《FORMOCRACY》为名的官僚制城市，市民办事必须提交层层叠叠的证明资料。',
  '常见证明资料包括：身份证、居住证明、医院急件、慢性病登记卡、水电缴费单、工作单位证明、主管担保函、污染暴露检测单、证据封存申请、主管保障券等。',
  '窗口工作要点：核验字段是否齐全、跨文件比对是否矛盾、判断辖区受理权，最后盖章决策——每一次盖章都会带来真实的公共后果。',
  '近期背景：第七码头发生了一起事故，相关材料正陆续涌入窗口，暗流涌动。',
  '说话风格：专业、克制，带一点公事公办的官腔，但对新人是善意的；偶尔流露猫的慵懒神态。',
  '回答要求：始终用中文，保持角色身份，不要暴露你是 AI 或大模型；单次回复控制在 2~4 句话，简洁清楚。',
  '若玩家问到与工作、世界观无关的话题，礼貌地把话题引回窗口业务。',
].join('\n');

export interface StreamReplyOptions {
  /** 玩家最新输入 */
  prompt: string;
  /** 已有对话历史（不含本次待生成的秘书回复） */
  history: ChatMessage[];
  /** 文本更新回调，text 为当前完整文本 */
  onText: (text: string) => void;
  /** 完成回调 */
  onDone: (finalText: string) => void;
  /** 取消控制 */
  signal?: AbortSignal;
}

/** 将历史对话与最新输入折叠为单轮 prompt，为无多轮记忆的接口补充上下文。 */
function buildContextPrompt(prompt: string, history: ChatMessage[]): string {
  const recent = history.slice(-8);
  if (recent.length === 0) return prompt;
  const lines = recent.map((m) => `${m.role === 'user' ? '玩家' : '秘书'}：${m.text}`);
  return [
    '以下是到目前为止的对话记录：',
    ...lines,
    `玩家：${prompt}`,
    '请以秘书的身份，用中文回复玩家最新的这句话。',
  ].join('\n');
}

/** 预览环境（无灵光宿主）下的兜底台词，保证 UI 可演示。 */
function localFallbackReply(prompt: string): string {
  const p = prompt.trim();
  if (p === '') return '受理员，请把您的问题说清楚，我这边好登记。';
  if (/你好|您好|hi|hello/i.test(p)) {
    return '受理员，欢迎来到第十二区行政窗口。有什么材料上的问题，尽管问我。（当前为本地预览，接入灵光宿主后我会正式为你解答。）';
  }
  return `关于「${p}」，按窗口规程需要先核验相关证明资料是否齐全。（当前为本地预览环境，正式发布到灵光后我会给出完整答复。）`;
}

/**
 * 向秘书发起一次对话请求，按可用能力自动降级。
 * 任一层级若未产出非空文本，则继续尝试下一层，最终本地兜底回复总能保证有输出。
 */
export async function streamSecretaryReply(options: StreamReplyOptions): Promise<void> {
  const { prompt, history, onText, onDone, signal } = options;
  const contextPrompt = buildContextPrompt(prompt, history);
  const isAborted = (): boolean => signal?.aborted === true;
  const lg = typeof window !== 'undefined' ? window.lingguang : undefined;

  // 1) 优先使用流式 LLM
  if (typeof lg?.ai.llmStream === 'function') {
    try {
      let latest = '';
      await lg.ai.llmStream({
        prompt: contextPrompt,
        systemPrompt: SECRETARY_SYSTEM_PROMPT,
        ...(signal !== undefined ? { signal } : {}),
        onText: (payload) => {
          latest = payload.text;
          onText(payload.text);
        },
      });
      if (latest.trim() !== '') {
        onDone(latest);
        return;
      }
    } catch {
      // 忽略，进入下一级兜底
    }
  }
  if (isAborted()) return;

  // 2) 退化到一次性 callLLM
  const callLLMFn = typeof window !== 'undefined' ? window.callLLM : undefined;
  if (typeof callLLMFn === 'function') {
    try {
      const res: unknown = await callLLMFn(contextPrompt, SECRETARY_SYSTEM_PROMPT);
      const text = extractText(res);
      if (text.trim() !== '') {
        onText(text);
        onDone(text);
        return;
      }
    } catch {
      // 忽略，进入下一级兜底
    }
  }
  if (isAborted()) return;

  // 3) 本地兜底（预览环境或上游均无输出时）
  const text = localFallbackReply(prompt);
  await new Promise((r) => setTimeout(r, 300));
  if (isAborted()) return;
  onText(text);
  onDone(text);
}

/** 从 callLLM 的多种可能返回结构中提取文本，无法提取时返回空串。 */
function extractText(res: unknown): string {
  if (typeof res === 'string') return res;
  if (typeof res === 'object' && res !== null) {
    const obj = res as Record<string, unknown>;
    for (const key of ['text', 'content', 'message', 'data', 'result']) {
      const v = obj[key];
      if (typeof v === 'string' && v.trim() !== '') return v;
    }
  }
  return '';
}
