import type { Worldview } from '@/types/content';
import SectionHeader from '@/components/SectionHeader';

interface WorldviewSectionProps {
  data: Worldview;
}

/** 世界观板块：标题、标语、概述、分节内容（封面/标签/联系方式已移至首页） */
function WorldviewSection({ data }: WorldviewSectionProps) {
  const intro = Array.isArray(data.intro) ? data.intro : [];
  const blocks = Array.isArray(data.blocks) ? data.blocks : [];

  return (
    <section data-testid="section-worldview" className="pb-4">
      <SectionHeader title={data.title} subtitle={data.tagline} />

      {intro.length > 0 ? (
        <div className="mb-6 space-y-3 rounded-2xl bg-slate-800/60 p-4 text-[15px] leading-relaxed text-slate-200">
          {intro.map((p, i) => (
            <p key={i}>{p}</p>
          ))}
        </div>
      ) : null}

      <div className="space-y-4">
        {blocks.map((block, i) => {
          const paragraphs = Array.isArray(block.paragraphs) ? block.paragraphs : [];
          return (
            <article
              key={`${block.heading}-${String(i)}`}
              className="rounded-2xl border border-slate-700/60 bg-slate-900/40 p-4"
            >
              <h2 className="mb-2 flex items-center gap-2 text-base font-semibold text-white">
                <span className="inline-block h-4 w-1 rounded-full bg-emerald-400" />
                {block.heading}
              </h2>
              <div className="space-y-2 text-[15px] leading-relaxed text-slate-300">
                {paragraphs.map((p, j) => (
                  <p key={j}>{p}</p>
                ))}
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

export default WorldviewSection;
