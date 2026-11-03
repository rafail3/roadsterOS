"use client";

import dynamic from "next/dynamic";

// The car rules run in the browser, so nothing here can render on the server.
const App = dynamic(
  async () => {
    const { bridgeLoaded } = await import("../car/bridge");
    return bridgeLoaded ? import("./Emulator") : import("./BridgeMissing");
  },
  { ssr: false },
);

export function ClientApp() {
  return <App />;
}
