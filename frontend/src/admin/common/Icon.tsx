import type { CSSProperties } from "react";

const paths = {
  overview: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  activity: "M3 12h4l3-8 4 16 3-8h4",
  quality: "M12 3l3 6 6 1-4.5 4.5 1 6.5-5.5-3-5.5 3 1-6.5L3 10l6-1z",
  cost: "M4 5h16v15H4z M4 8h16 M15 12h5v5h-5z M7 3h10",
  arrow: "M5 12h14 M13 6l6 6-6 6",
  calendar: "M4 5h16v16H4z M4 10h16 M8 3v4 M16 3v4",
} as const;
export function Icon({ name, style }: { name: keyof typeof paths; style?: CSSProperties }) {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={style}><path d={paths[name]} /></svg>;
}
