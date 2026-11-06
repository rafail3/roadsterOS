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
export function UnitPicture({ width = 300 }: { width?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const scale = width / FRAME.width;
  // the screen is drawn at the size it shows, one canvas pixel per screen pixel, so the dots stay sharp
  const shown = { left: 100 * scale, top: 36 * scale, width: 1024 * scale, height: 600 * scale };
  const density = 0.8 * (typeof window === "undefined" ? 1 : window.devicePixelRatio || 1);
  const pixels = { width: Math.round(shown.width * density), height: Math.round(shown.height * density) };
  useEffect(() => {
    const c = ref.current?.getContext("2d");
    if (!c) return;
    c.setTransform(pixels.width / 1024, 0, 0, pixels.height / 600, 0, 0);
    // the boot picture as it stands once complete, without the moving refresh band
    drawBoot(c, 9999, true);
  }, [pixels.width, pixels.height]);
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
        </div>
        <canvas ref={ref} className="unit-mini-screen" width={pixels.width} height={pixels.height} style={shown} />
      </div>
      <CubeMark size={Math.round(FRAME.height * scale * 0.74)} />
    </div>
  );
}
