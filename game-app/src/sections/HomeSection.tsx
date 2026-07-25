import type { SectionKey, Worldview } from '@/types/content';
import { Globe2, Users, Cat, ChevronRight } from 'lucide-react';
import coverImage from '@/assets/cover.png';

interface HomeSectionProps {
  worldview: Worldview;
  onNavigate: (key: SectionKey) => void;
}

interface MenuItem {
  key: SectionKey;
  label: string;
  desc: string;
  Icon: typeof Globe2;
}

const MENU: MenuItem[] = [
  { key: 'worldview', label: '世界观', desc: '衡川市第十二区的制度与现实', Icon: Globe2 },
  { key: 'npcs', label: '人物设定', desc: '登场角色与背景设定', Icon: Users },
  { key: 'secretary', label: '秘书对话', desc: '与窗口秘书 AI 对话', Icon: Cat },
];

/** 首页启动页：封面 + 标语 + 标签 + 三个导航按钮 + 联系方式 */
function HomeSection({ worldview, onNavigate }: HomeSectionProps) {
  const keywords = Array.isArray(worldview.keywords) ? worldview.keywords : [];
  const contact = Array.isArray(worldview.contact) ? worldview.contact : [];

  return (
    <main
      data-testid="section-home"
      className="home-scroll mx-auto flex w-full max-w-xl flex-col px-5 pt-6"
    >
      {/* 封面 */}
      <img
        src={coverImage}
        alt="FORMOCRACY 表面政治 封面"
        data-testid="home-cover"
        className="w-full rounded-2xl border border-slate-700/60 object-cover shadow-lg shadow-black/40"
      />

      {/* 小字标语 */}
      <p className="mt-4 text-center text-sm text-emerald-300/90">{worldview.tagline}</p>

      {/* 关键词标签 */}
      {keywords.length > 0 ? (
        <div className="mt-3 flex flex-wrap justify-center gap-2">
          {keywords.map((kw, i) => (
            <span
              key={`${kw}-${String(i)}`}
              className="rounded-full border border-emerald-400/40 bg-emerald-400/10 px-3 py-1 text-xs text-emerald-200"
            >
              {kw}
            </span>
          ))}
        </div>
      ) : null}

      {/* 三个导航按钮 */}
      <nav className="mt-6 space-y-3">
        {MENU.map(({ key, label, desc, Icon }) => (
          <button
            key={key}
            type="button"
            data-testid={`home-nav-${key}`}
            onClick={() => {
              onNavigate(key);
            }}
            className="flex w-full items-center gap-3 rounded-2xl border border-slate-700/60 bg-slate-900/50 px-4 py-3.5 text-left transition-colors hover:border-emerald-400/50 hover:bg-slate-800/70"
          >
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-emerald-400/10 text-emerald-400">
              <Icon size={22} strokeWidth={2} />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-base font-semibold text-white">{label}</span>
              <span className="block truncate text-xs text-slate-400">{desc}</span>
            </span>
            <ChevronRight size={18} className="shrink-0 text-slate-500" />
          </button>
        ))}
      </nav>

      {/* 联系方式 */}
      {contact.length > 0 ? (
        <div className="mt-7 border-t border-slate-800 pt-5 text-center">
          <p className="mb-2 text-xs font-medium uppercase tracking-widest text-slate-500">联系我们</p>
          {contact.map((line, i) => (
            <p key={i} className="text-sm text-slate-300">
              {line}
            </p>
          ))}
        </div>
      ) : null}
    </main>
  );
}

export default HomeSection;
