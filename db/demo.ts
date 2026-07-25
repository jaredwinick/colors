import type { CaptureView, PaletteColor } from "./captures";

const palettes: [string, number][][] = [
  [["#9EBBD0", .24], ["#B4CBD9", .22], ["#CFD9D8", .19], ["#E8DED2", .18], ["#D4B29E", .17]],
  [["#829FB8", .25], ["#AFC4D2", .23], ["#D5D9D4", .18], ["#E8D1BC", .18], ["#C89C82", .16]],
  [["#6E8EAA", .25], ["#A7BAC7", .22], ["#D9D4CB", .20], ["#E7B997", .18], ["#B77D69", .15]],
  [["#5C7E9D", .23], ["#93ADBE", .21], ["#D6D1C5", .19], ["#E8AC84", .20], ["#AA6A5D", .17]],
  [["#4D6E8D", .22], ["#839FAF", .19], ["#C9C7BC", .18], ["#E59A70", .22], ["#965552", .19]],
  [["#405D79", .23], ["#708B9D", .18], ["#B7B9B1", .17], ["#DC865F", .22], ["#7D4650", .20]],
  [["#344B65", .24], ["#5F7487", .19], ["#969DA0", .17], ["#C87258", .20], ["#643B4B", .20]],
  [["#28374F", .25], ["#48596C", .20], ["#777E87", .18], ["#A75E55", .19], ["#483346", .18]],
  [["#1E293D", .28], ["#354358", .22], ["#596170", .19], ["#7B4D56", .17], ["#332C3F", .14]],
  [["#182134", .30], ["#29344A", .23], ["#444A5D", .18], ["#584052", .16], ["#27283A", .13]],
  [["#11192A", .31], ["#202B40", .23], ["#343B50", .19], ["#413548", .15], ["#202235", .12]],
  [["#0E1727", .32], ["#1C273A", .24], ["#2B3448", .18], ["#353044", .15], ["#1A1E30", .11]],
];

function shiftedDate(hoursAgo: number) {
  return new Date(Date.now() - hoursAgo * 60 * 60 * 1000).toISOString();
}

export const demoCaptures: CaptureView[] = Array.from({ length: 24 }, (_, index) => {
  const palette = palettes[index % palettes.length].map(([hex, weight]) => ({
    hex: hex as string,
    weight: weight as number,
  }));

  return {
    id: `sample-${index}`,
    capturedAt: shiftedDate(index),
    imageUrl: null,
    palette,
  };
});
