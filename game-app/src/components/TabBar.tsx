import type { SectionKey } from '@/types/content';
import { Home, Globe2, Users, Cat } from 'lucide-react';

interface TabItem {
  key: SectionKey;
  label: string;
  Icon: typeof Globe2;
}

const TABS: TabItem[] = [
  { key: 'home', label: '首页', Icon: Home },
  { key: 'worldview', label: '世界观', Icon: Globe2 },
  { key: 'npcs', label: '人物设定', Icon: Users },
  { key: 'secretary', label: 'AI秘书', Icon: Cat },
];

interface TabBarProps {
  active: SectionKey;
  onChange: (key: SectionKey) => void;
}

/** 底部三板块切换导航。安全区 padding 在 index.css 中通过 env() 处理。 */
function TabBar({ active, onChange }: TabBarProps) {
  return (
    <nav
      data-testid="tab-bar"
      className="tab-bar fixed inset-x-0 bottom-0 z-20 flex h-16 items-stretch justify-around border-t border-slate-700/60 bg-slate-900/95 backdrop-blur"
    >
      {TABS.map(({ key, label, Icon }) => {
        const isActive = key === active;
        return (
          <button
            key={key}
            type="button"
            data-testid={`tab-${key}`}
            aria-pressed={isActive}
            onClick={() => {
              onChange(key);
            }}
            className={`flex flex-1 flex-col items-center gap-1 py-2.5 text-xs transition-colors ${
              isActive ? 'text-emerald-400' : 'text-slate-400'
            }`}
          >
            <Icon size={22} strokeWidth={isActive ? 2.4 : 1.8} />
            <span className={isActive ? 'font-semibold' : ''}>{label}</span>
          </button>
        );
      })}
    </nav>
  );
}

export default TabBar;
