import type { ListeningSeat } from "../car/bridge";

/** The car picture's own pixels (images/car-top.webp, front at the top); everything below is placed on them. */
export const CAR_VIEW = { w: 458, h: 800 };

/** The four seats under the roof, in the processor's speaker order. */
export const CAR_SEATS: { id: ListeningSeat; x: number; y: number; w: number; h: number }[] = [
  { id: "FRONT_LEFT", x: 162, y: 344, w: 60, h: 84 },
  { id: "FRONT_RIGHT", x: 238, y: 344, w: 60, h: 84 },
  { id: "REAR_LEFT", x: 162, y: 470, w: 60, h: 84 },
  { id: "REAR_RIGHT", x: 238, y: 470, w: 60, h: 84 },
];
/** A speaker in each door. */
export const CAR_SPEAKERS = [[94, 376], [364, 376], [94, 504], [364, 504]];

/**
 * The car from above: the picture, with the seats drawn over its roof (lit where the sound is focused) and a speaker
 * on each door. One static image, drawn once.
 */
export function CarTop({ lit }: { lit?: (seat: ListeningSeat) => boolean }) {
  return (
    <svg className="car-top" viewBox={`0 0 ${CAR_VIEW.w} ${CAR_VIEW.h}`} aria-hidden="true">
      <image href="/images/car-top.webp" x="0" y="0" width={CAR_VIEW.w} height={CAR_VIEW.h} />
      {CAR_SEATS.map((s) => {
        const on = lit?.(s.id) ?? false;
        return (
          <g key={s.id} className={`car-seat${on ? " is-lit" : ""}`}>
            <rect x={s.x} y={s.y} width={s.w} height={s.h * 0.62} rx="14" />
            <rect x={s.x + 4} y={s.y + s.h * 0.66} width={s.w - 8} height={s.h * 0.22} rx="8" />
            <rect x={s.x + s.w / 2 - 12} y={s.y + s.h * 0.9} width="24" height={s.h * 0.1} rx="5" />
          </g>
        );
      })}
      {CAR_SPEAKERS.map(([cx, cy]) => (
        <g key={`${cx}-${cy}`} className="car-speaker">
          <circle cx={cx} cy={cy} r="13" />
          <circle cx={cx} cy={cy} r="5" />
        </g>
      ))}
    </svg>
  );
}
