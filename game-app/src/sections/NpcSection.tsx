import type { Npc } from '@/types/content';
import SectionHeader from '@/components/SectionHeader';

interface NpcSectionProps {
  npcs: Npc[];
}

/** NPC 人物设定板块：卡片列表，左侧全身立绘 + 右侧身份、设定、特征标签与台词 */
function NpcSection({ npcs }: NpcSectionProps) {
  const list = Array.isArray(npcs) ? npcs : [];

  return (
    <section data-testid="section-npcs" className="pb-4">
      <SectionHeader title="人物设定" subtitle="登场角色与背景设定" />

      <div className="space-y-4">
        {list.map((npc) => {
          const description = Array.isArray(npc.description) ? npc.description : [];
          const traits = Array.isArray(npc.traits) ? npc.traits : [];
          const hasAvatar = typeof npc.avatar === 'string' && npc.avatar !== '';
          const initial = typeof npc.name === 'string' && npc.name.length > 0 ? npc.name[0] : '?';

          return (
            <article
              key={npc.id}
              data-testid={`npc-${npc.id}`}
              className="flex overflow-hidden rounded-2xl border border-slate-700/60 bg-slate-900/40"
            >
              {/* 左侧立绘栏，随右侧文字高度自适应拉伸 */}
              <div className="relative w-24 shrink-0 bg-gradient-to-b from-slate-800/80 to-slate-950 sm:w-28">
                {hasAvatar ? (
                  <img
                    src={npc.avatar}
                    alt={npc.name}
                    className="absolute inset-0 h-full w-full object-contain object-bottom p-1.5"
                  />
                ) : (
                  <div className="absolute inset-0 flex items-center justify-center">
                    <span className="text-3xl font-bold text-emerald-300/80">{initial}</span>
                  </div>
                )}
                <span className="absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-slate-950 to-transparent" />
              </div>

              {/* 右侧信息栏 */}
              <div className="min-w-0 flex-1 space-y-3 p-4">
                <div className="min-w-0">
                  <h2 className="truncate text-lg font-bold text-white">{npc.name}</h2>
                  <p className="mt-0.5 text-xs font-medium text-emerald-300">{npc.role}</p>
                  <p className="mt-0.5 text-xs text-slate-400">{npc.tagline}</p>
                </div>

                {traits.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5">
                    {traits.map((t, i) => (
                      <span
                        key={`${t}-${String(i)}`}
                        className="rounded-md bg-slate-700/60 px-2 py-0.5 text-xs text-slate-200"
                      >
                        {t}
                      </span>
                    ))}
                  </div>
                ) : null}

                <div className="space-y-2 text-sm leading-relaxed text-slate-300">
                  {description.map((p, i) => (
                    <p key={i}>{p}</p>
                  ))}
                </div>

                {npc.quote !== undefined && npc.quote !== '' ? (
                  <blockquote className="border-l-2 border-emerald-400/70 pl-3 text-sm italic text-slate-400">
                    「{npc.quote}」
                  </blockquote>
                ) : null}
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

export default NpcSection;
