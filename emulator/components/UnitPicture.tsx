import { useEffect, useRef } from "react";
import { CubeMark, drawBoot } from "./Boot";

/** The unit at full size, scaled down as a whole: the screen and the frame around it. */
const FRAME = { width: 1154, height: 676 };
const KEYS: { icon: string; sign?: string }[] = [
  { icon: "i-power" }, { icon: "i-house" }, { icon: "i-back" }, { icon: "i-speaker-simple", sign: "+" }, { icon: "i-speaker-simple", sign: "−" },
];

/**
 * About's picture of the unit: the piano-black frame with its pinholes, touch keys and reflection, the boot picture
 * on its screen, and the symbol beside it. [width] in canvas pixels.
 */
export function UnitPicture({ width = 210 }: { width?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const scale = width / FRAME.width;
  useEffect(() => {
    const c = ref.current?.getContext("2d");
    // the boot picture as it stands once complete, without the moving refresh band
    if (c) drawBoot(c, 9999, true);
  }, []);
  return (
    <div className="about-hero" aria-hidden="true">
      <div className="unit-mini" style={{ width, height: FRAME.height * scale }}>
        <div className="unit-mini-bezel" style={{ transform: `scale(${scale})` }}>
          <div className="unit-mini-keys">
            <span className="unit-mini-pin"><i />MIC</span>
            <span className="unit-mini-pin is-rst"><i />RST</span>
            {KEYS.map((k, i) => (
              <span key={i} className="unit-mini-key"><span className={`icon ${k.icon}`} />{k.sign && <span className="unit-mini-sign">{k.sign}</span>}</span>
            ))}
          </div>
          <canvas ref={ref} className="unit-mini-screen" width={1024} height={600} />
        </div>
      </div>
      <CubeMark size={Math.round(FRAME.height * scale * 0.7)} />
    </div>
  );
}
