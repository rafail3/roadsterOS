// The emulator's cover memory. GET lists the kept covers with their sizes; GET ?name= serves one. POST {name, data}
// keeps an image sent as a data URL; POST {name, title, artist} looks the track up in the catalogs and keeps the result.
import { largeCover } from "../../../server/catalog";
import { contentType, list, put, read, validName } from "../../../server/coverStore";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  const name = new URL(request.url).searchParams.get("name");
  if (name === null) return Response.json(list());
  const bytes = validName(name) ? read(name) : null;
  if (!bytes) return new Response(null, { status: 404 });
  return new Response(new Uint8Array(bytes), { headers: { "Content-Type": contentType(bytes), "Cache-Control": "no-store" } });
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as { name?: string; data?: string; title?: string; artist?: string } | null;
  if (!body?.name || !validName(body.name)) return new Response(null, { status: 400 });
  if (body.data) {
    const base64 = body.data.slice(body.data.indexOf(",") + 1);
    return Response.json(put(body.name, Buffer.from(base64, "base64")));
  }
  if (!body.title) return new Response(null, { status: 400 });
  const bytes = await largeCover(body.title, body.artist ?? "");
  if (!bytes) return Response.json({ found: false });
  return Response.json({ found: true, ...put(body.name, bytes) });
}
