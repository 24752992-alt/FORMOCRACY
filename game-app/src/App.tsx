import './App.css';
import { useState } from 'react';
import type { SectionKey } from '@/types/content';
import { gameContent } from '@/data/gameContent';
import TabBar from '@/components/TabBar';
import HomeSection from '@/sections/HomeSection';
import WorldviewSection from '@/sections/WorldviewSection';
import NpcSection from '@/sections/NpcSection';
import SecretarySection from '@/sections/SecretarySection';

function App() {
  const [active, setActive] = useState<SectionKey>('home');

  return (
    // container 为应用根容器，背景色设置在此层
    <div id="container" className="app-root min-h-screen bg-slate-950 text-slate-100">
      {active === 'home' ? <HomeSection worldview={gameContent.worldview} onNavigate={setActive} /> : null}

      {active === 'worldview' || active === 'npcs' ? (
        <main className="app-scroll mx-auto w-full max-w-xl px-5 pt-6">
          {active === 'worldview' ? <WorldviewSection data={gameContent.worldview} /> : null}
          {active === 'npcs' ? <NpcSection npcs={gameContent.npcs} /> : null}
        </main>
      ) : null}

      {/* 秘书对话常驻挂载，靠 hidden 切换显隐，以保留对话历史 */}
      <SecretarySection hidden={active !== 'secretary'} />

      {/* 首页为启动页，不显示底部导航；进入板块后显示导航（含返回首页） */}
      {active !== 'home' ? <TabBar active={active} onChange={setActive} /> : null}
    </div>
  );
}

export default App;
