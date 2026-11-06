import type { ListeningSeat } from "../car/bridge";

/** The drawing's space: the car picture (images/car-top.webp, 359x800, front at the top) with room beside it for the speakers. */
export const CAR_VIEW = { w: 459, h: 800 };
const PICTURE = { x: 50, w: 359 };

/** The four seats under the roof, in the processor's speaker order. */
export const CAR_SEATS: { id: ListeningSeat; x: number; y: number; w: number; h: number }[] = [
  { id: "FRONT_LEFT", x: 172, y: 336, w: 54, h: 80 },
  { id: "FRONT_RIGHT", x: 234, y: 336, w: 54, h: 80 },
  { id: "REAR_LEFT", x: 172, y: 464, w: 54, h: 80 },
  { id: "REAR_RIGHT", x: 234, y: 464, w: 54, h: 80 },
];
/** A speaker by each door, just outside the body, so the four read at a glance. */
export const CAR_SPEAKERS = [[24, 382], [435, 382], [24, 512], [435, 512]];

/** How loud each speaker plays for a balance and fader (−7..7 each): 1 in the centre, down towards 0 away from it. */
export function speakerLevels(balance: number, fader: number): number[] {
  const left = balance > 0 ? 1 - balance / 7 : 1, right = balance < 0 ? 1 + balance / 7 : 1;
  const front = fader < 0 ? 1 + fader / 7 : 1, rear = fader > 0 ? 1 - fader / 7 : 1;
  return [left * front, right * front, left * rear, right * rear];
}

/** The speaker picture (images/speaker.webp, 160x170, its cone facing right). */
const SPEAKER = { w: 62, h: 66 };

/** A speaker turned towards the cabin, with sound waves as loud as it plays. */
function Speaker({ cx, cy, level, side }: { cx: number; cy: number; level: number; side: 1 | -1 }) {
  const wave = (r: number) => {
    const a = Math.PI / 4.2;
    const x1 = cx + side * r * Math.cos(a), y1 = cy - r * Math.sin(a), y2 = cy + r * Math.sin(a);
    return `M${x1} ${y1} A${r} ${r} 0 0 ${side > 0 ? 1 : 0} ${x1} ${y2}`;
  };
  return (
    <g className="car-speaker" style={{ opacity: 0.4 + 0.6 * level }}>
      {/* the picture faces right: the left side's speakers as they are, the right side's mirrored */}
      <image href="/images/speaker.webp" x={-SPEAKER.w / 2} y={-SPEAKER.h / 2} width={SPEAKER.w} height={SPEAKER.h}
        transform={`translate(${cx} ${cy}) scale(${side} 1)`} />
      {level > 0.02 && <path d={wave(40)} className="spk-wave" style={{ opacity: level }} />}
      {level > 0.5 && <path d={wave(50)} className="spk-wave" style={{ opacity: (level - 0.5) * 2 }} />}
    </g>
  );
}

/**
 * The car from above: the picture, with the seats drawn over its roof (lit where the sound is focused) and a speaker
 * on each door. One static image, drawn once.
 */
export function CarTop({ lit, levels = [1, 1, 1, 1] }: { lit?: (seat: ListeningSeat) => boolean; levels?: number[] }) {
  return (
    <svg className="car-top" viewBox={`0 0 ${CAR_VIEW.w} ${CAR_VIEW.h}`} aria-hidden="true">
      <image href="/images/car-top.webp" x={PICTURE.x} y="0" width={PICTURE.w} height={CAR_VIEW.h} />
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
      {CAR_SPEAKERS.map(([cx, cy], i) => <Speaker key={i} cx={cx} cy={cy} level={levels[i]} side={i % 2 ? -1 : 1} />)}
    </svg>
  );
}
