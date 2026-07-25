interface SectionHeaderProps {
  /** 板块主标题 */
  title: string;
  /** 副标题 / 标语（可选） */
  subtitle?: string;
}

/** 各板块顶部统一的标题样式 */
function SectionHeader({ title, subtitle }: SectionHeaderProps) {
  return (
    <header className="mb-5">
      <div className="h-1 w-10 rounded-full bg-emerald-400" />
      <h1 className="mt-3 text-2xl font-bold leading-tight text-white">{title}</h1>
      {subtitle !== undefined && subtitle !== '' ? (
        <p className="mt-1.5 text-sm text-emerald-300/90">{subtitle}</p>
      ) : null}
    </header>
  );
}

export default SectionHeader;
