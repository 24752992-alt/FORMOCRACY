import type { GameFlow } from '@/types/content';
import SectionHeader from '@/components/SectionHeader';

interface FlowSectionProps {
  data: GameFlow;
}

/** 游戏流程板块：竖向时间线，按步骤展示流程 */
function FlowSection({ data }: FlowSectionProps) {
  const intro = Array.isArray(data.intro) ? data.intro : [];
  const steps = Array.isArray(data.steps) ? data.steps : [];

  return (
    <section data-testid="section-flow" className="pb-4">
      <SectionHeader title="游戏流程" subtitle="从开始到通关的核心路径" />

      {intro.length > 0 ? (
        <div className="mb-6 space-y-3 rounded-2xl bg-slate-800/60 p-4 text-[15px] leading-relaxed text-slate-200">
          {intro.map((p, i) => (
            <p key={i}>{p}</p>
          ))}
        </div>
      ) : null}

      <ol className="relative space-y-5 border-l border-slate-700/70 pl-6">
        {steps.map((step) => {
          const description = Array.isArray(step.description) ? step.description : [];
          return (
            <li key={step.order} data-testid={`flow-step-${String(step.order)}`} className="relative">
              <span className="absolute -left-[31px] flex h-6 w-6 items-center justify-center rounded-full bg-emerald-400 text-xs font-bold text-slate-900">
                {step.order}
              </span>
              <div className="rounded-2xl border border-slate-700/60 bg-slate-900/40 p-4">
                <h2 className="mb-2 text-base font-semibold text-white">{step.title}</h2>
                <div className="space-y-2 text-[15px] leading-relaxed text-slate-300">
                  {description.map((p, i) => (
                    <p key={i}>{p}</p>
                  ))}
                </div>
                {step.tip !== undefined && step.tip !== '' ? (
                  <p className="mt-3 rounded-lg bg-emerald-400/10 px-3 py-2 text-xs text-emerald-200">
                    提示：{step.tip}
                  </p>
                ) : null}
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

export default FlowSection;
