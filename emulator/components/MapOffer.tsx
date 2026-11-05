import { bridge } from "../car/bridge";
import { declinePack, downloadPack, mapOffer, shellAction, useEmulator } from "../car/store";

/**
 * Over any screen, at the bottom: the map of the country the car just entered, when it is not on the unit yet.
 * Over Wi-Fi it offers the download; off Wi-Fi it asks for Wi-Fi first. A running download shows its progress.
 */
export function MapOffer({ hidden }: { hidden: boolean }) {
  const emulator = useEmulator();
  const { country, maps } = emulator;
  const offer = mapOffer(emulator);
  const downloading = maps.downloading;
  const code = downloading?.code ?? country;
  const name = code ? bridge.countryName(code) : "";
  const shown = !hidden && (offer !== "NONE" || downloading !== null);
  const waiting = downloading !== null && emulator.wifi.connected === null;

  return (
    <div className={`map-offer${shown ? " is-shown" : ""}`} role="status" aria-live="polite" inert={!shown}>
      <span className="map-offer-icon" aria-hidden="true"><span className="icon i-navigation-arrow-fill" /></span>
      {downloading ? (
        <span className="map-offer-text">
          <span className="map-offer-title">{waiting ? `Waiting for Wi-Fi · ${name}` : `Downloading the map of ${name}`}</span>
          <span className="map-offer-bar"><span style={{ transform: `scaleX(${downloading.progress})` }} /></span>
        </span>
      ) : (
        <>
          <span className="map-offer-text">
            <span className="map-offer-title">You are in {name}</span>
            <span className="map-offer-sub">{offer === "DOWNLOAD" ? `Download its offline map, ${bridge.packMb(code) || "?"} MB` : "Connect to Wi-Fi to download its map"}</span>
          </span>
          <button className="map-offer-button is-quiet" onClick={() => declinePack(code)}>Not now</button>
          {offer === "DOWNLOAD"
            ? <button className="map-offer-button" onClick={() => downloadPack(code)}>Download</button>
            : <button className="map-offer-button" onClick={() => shellAction({ type: "OpenNetworks" })}>Wi-Fi</button>}
        </>
      )}
    </div>
  );
}
