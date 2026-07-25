import { useEffect, useRef, useState } from 'react';
import { Send, Cat } from 'lucide-react';
import type { ChatMessage } from '@/types/content';
import { streamSecretaryReply } from '@/lib/secretaryChat';
import avatarSecretary from '@/assets/npc/secretary_idle.png';

/** 生成消息唯一 id */
function makeId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${String(Date.now())}-${String(Math.floor(Math.random() * 1e6))}`;
}

const WELCOME: ChatMessage = {
  id: 'welcome',
  role: 'secretary',
  text: '受理员，欢迎来到第十二区行政窗口。我是这里的秘书——关于世界观、证明资料或受理流程，有什么想问的尽管开口。',
};

const QUICK_ASKS = ['这座城市是什么样的？', '受理一份材料要看哪些字段？', '第七码头出了什么事？'];

interface SecretarySectionProps {
  /** 非激活态时隐藏（保留组件挂载以保存对话状态） */
  hidden: boolean;
}

/** AI 秘书对话板块：玩家与秘书进行文字对话。 */
function SecretarySection({ hidden }: SecretarySectionProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([WELCOME]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  // 新消息时滚动到底部
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // 卸载时中断流式请求
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  function send(raw: string) {
    const text = raw.trim();
    if (text === '' || sending) return;

    const history = messages;
    const userMsg: ChatMessage = { id: makeId(), role: 'user', text };
    const replyId = makeId();
    const replyMsg: ChatMessage = { id: replyId, role: 'secretary', text: '', pending: true };

    setMessages([...history, userMsg, replyMsg]);
    setInput('');
    setSending(true);

    const controller = new AbortController();
    abortRef.current = controller;

    const patch = (updater: (m: ChatMessage) => ChatMessage) => {
      setMessages((prev) => prev.map((m) => (m.id === replyId ? updater(m) : m)));
    };

    void streamSecretaryReply({
      prompt: text,
      history,
      signal: controller.signal,
      onText: (t) => {
        patch((m) => ({ ...m, text: t }));
      },
      onDone: (finalText) => {
        patch((m) => ({ ...m, text: finalText.trim() === '' ? '（秘书没有给出答复，请稍后再试。）' : finalText, pending: false }));
        setSending(false);
      },
    });
  }

  return (
    <section
      data-testid="section-secretary"
      className="chat-shell"
      style={hidden ? { display: 'none' } : undefined}
      aria-hidden={hidden}
    >
      {/* 顶部秘书信息栏 */}
      <header className="flex items-center gap-3 border-b border-slate-700/60 bg-slate-900/80 px-4 py-3 backdrop-blur">
        <img
          src={avatarSecretary}
          alt="秘书"
          className="h-11 w-11 shrink-0 rounded-full border border-emerald-400/40 bg-slate-800 object-contain p-0.5"
        />
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 text-sm font-semibold text-slate-100">
            <Cat size={15} className="text-emerald-400" />
            秘书
          </p>
          <p className="truncate text-xs text-slate-400">第十二区行政窗口 · 在线</p>
        </div>
      </header>

      {/* 消息列表 */}
      <div ref={scrollRef} data-testid="secretary-messages" className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
        {messages.map((m) => {
          const isUser = m.role === 'user';
          return (
            <div key={m.id} data-testid={`msg-${m.id}`} className={`flex gap-2.5 ${isUser ? 'flex-row-reverse' : ''}`}>
              {!isUser && (
                <img
                  src={avatarSecretary}
                  alt="秘书"
                  className="mt-0.5 h-8 w-8 shrink-0 rounded-full border border-slate-700 bg-slate-800 object-contain p-0.5"
                />
              )}
              <div
                className={`max-w-[76%] whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2 text-sm leading-relaxed ${
                  isUser
                    ? 'rounded-tr-sm bg-emerald-500/90 text-slate-950'
                    : 'rounded-tl-sm bg-slate-800/80 text-slate-100'
                }`}
              >
                {m.text}
                {m.pending === true && m.text === '' ? <span className="text-slate-400">正在输入…</span> : null}
                {m.pending === true && m.text !== '' ? <span className="ml-0.5 animate-pulse text-emerald-300">▍</span> : null}
              </div>
            </div>
          );
        })}
      </div>

      {/* 快捷提问 */}
      {messages.length <= 1 ? (
        <div className="flex flex-wrap gap-2 px-4 pb-2">
          {QUICK_ASKS.map((q) => (
            <button
              key={q}
              type="button"
              data-testid={`quick-${q}`}
              disabled={sending}
              onClick={() => {
                send(q);
              }}
              className="rounded-full border border-slate-700 bg-slate-800/60 px-3 py-1.5 text-xs text-slate-300 transition-colors hover:border-emerald-400/60 hover:text-emerald-300 disabled:opacity-50"
            >
              {q}
            </button>
          ))}
        </div>
      ) : null}

      {/* 输入栏 */}
      <div className="flex items-end gap-2 border-t border-slate-700/60 bg-slate-900/80 px-3 py-2.5 backdrop-blur">
        <input
          data-testid="secretary-input"
          value={input}
          onChange={(e) => {
            setInput(e.target.value);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
              e.preventDefault();
              send(input);
            }
          }}
          placeholder="向秘书提问…"
          className="min-w-0 flex-1 rounded-full border border-slate-700 bg-slate-800/80 px-4 py-2.5 text-sm text-slate-100 placeholder:text-slate-500 focus:border-emerald-400/60 focus:outline-none"
        />
        <button
          type="button"
          data-testid="secretary-send"
          disabled={sending || input.trim() === ''}
          onClick={() => {
            send(input);
          }}
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-emerald-500 text-slate-950 transition-opacity disabled:opacity-40"
          aria-label="发送"
        >
          <Send size={18} />
        </button>
      </div>
    </section>
  );
}

export default SecretarySection;
