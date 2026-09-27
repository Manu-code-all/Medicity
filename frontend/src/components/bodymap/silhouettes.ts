import type { BodyView } from "./taxonomy";

/**
 * The body, drawn as the areas that can be tapped (viewBox 0 0 200 440).
 * Each area is one path, even when it is two shapes (both arms, both legs),
 * so each is one focus stop and one answer. `pin` is where the marker sits
 * when the area is chosen.
 */
export interface RegionShape {
  id: string;
  d: string;
  pin: [number, number];
}

const HEAD = "M100 12C116 12 126 26 126 44C126 62 115 74 100 74C85 74 74 62 74 44C74 26 84 12 100 12Z";
const ARMS =
  "M58 104Q44 108 42 126L34 200L28 262Q27 276 36 277Q45 277 45 264L52 204L58 152Z" +
  "M142 104Q156 108 158 126L166 200L172 262Q173 276 164 277Q155 277 155 264L148 204L142 152Z";
const LEGS =
  "M66 290L98 304L96 420Q96 432 86 432L74 432Q65 432 69 420L67 356Z" +
  "M134 290L102 304L104 420Q104 432 114 432L126 432Q135 432 131 420L133 356Z";

export const SILHOUETTES: Record<BodyView, RegionShape[]> = {
  front: [
    { id: "head_face", d: HEAD, pin: [100, 44] },
    { id: "neck_throat", d: "M88 72L112 72L114 97L86 97Z", pin: [100, 86] },
    { id: "chest", d: "M60 105Q72 95 86 97L114 97Q128 95 140 105L142 166L58 166Z", pin: [100, 132] },
    { id: "abdomen_upper", d: "M58 168L142 168L140 212L60 212Z", pin: [100, 190] },
    { id: "abdomen_lower", d: "M60 214L140 214L138 250L62 250Z", pin: [100, 232] },
    { id: "pelvis_groin", d: "M62 252L138 252L132 286L110 300L90 300L68 286Z", pin: [100, 272] },
    { id: "joints_upper", d: ARMS, pin: [38, 214] },
    { id: "joints_lower", d: LEGS, pin: [82, 380] },
  ],
  back: [
    { id: "head_face", d: HEAD, pin: [100, 44] },
    {
      id: "cervical_spine",
      d: "M88 72L112 72L114 97Q128 95 140 105L141 128L59 128L60 105Q72 95 86 97Z",
      pin: [100, 108],
    },
    {
      id: "thoracic_lumbar",
      d: "M59 130L141 130L140 168L120 170L120 244L139 246L136 266L132 288L110 302L90 302L68 288L64 266L61 246L80 244L80 170L60 168Z",
      pin: [100, 206],
    },
    { id: "flanks", d: "M58 170L78 170L78 242L60 242Z" + "M122 170L142 170L140 242L122 242Z", pin: [68, 206] },
    { id: "joints_upper", d: ARMS, pin: [38, 214] },
    { id: "joints_lower", d: LEGS, pin: [82, 380] },
  ],
};
