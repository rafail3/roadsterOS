import type { Metadata, Viewport } from "next";
import Script from "next/script";
import type { ReactNode } from "react";
import "../styles/tokens.css";
import "../styles/screen.css";
import "../styles/clock.css";
import "../styles/music.css";
import "maplibre-gl/dist/maplibre-gl.css";
import "../styles/radio.css";
import "../styles/phone.css";
import "../styles/maps.css";
import "../styles/chrome.css";

export const metadata: Metadata = { title: "roadsterOS emulator" };
export const viewport: Viewport = { colorScheme: "dark", themeColor: "#0c0e12" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <head>
        <link rel="preload" href="/fonts/geist-latin-wght-normal.woff2" as="font" type="font/woff2" crossOrigin="" />
      </head>
      <body>
        {children}
        <Script src="/rdstr.js" strategy="beforeInteractive" />
      </body>
    </html>
  );
}
