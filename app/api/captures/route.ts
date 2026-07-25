import { getRecentCaptures } from "../../../db/captures";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    const captures = await getRecentCaptures(24);
    return Response.json(
      { captures },
      {
        headers: {
          "Cache-Control": "public, max-age=30, stale-while-revalidate=120",
        },
      },
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unable to read captures";
    return Response.json({ error: message }, { status: 500 });
  }
}
