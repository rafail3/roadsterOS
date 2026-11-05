/** Wi-Fi signal as the three arcs and the dot; arcs above the level are drawn faint. Level 0: all faint. */
export function WifiBars({ level, className }: { level: 0 | 1 | 2 | 3; className?: string }) {
  const arc = (n: number) => ({ opacity: level >= n ? 1 : 0.3 });
  return (
    <svg className={`wifi-bars${className ? ` ${className}` : ""}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true">
      <path d="M2.6 9.2a13.4 13.4 0 0 1 18.8 0" style={arc(3)} />
      <path d="M5.8 12.5a8.8 8.8 0 0 1 12.4 0" style={arc(2)} />
      <path d="M9 15.7a4.3 4.3 0 0 1 6 0" style={arc(1)} />
      <circle cx="12" cy="19" r="1.6" fill="currentColor" stroke="none" style={{ opacity: level >= 1 ? 1 : 0.3 }} />
    </svg>
  );
}
